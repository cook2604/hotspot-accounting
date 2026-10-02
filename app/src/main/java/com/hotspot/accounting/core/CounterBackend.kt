package com.hotspot.accounting.core

import android.util.Log
import com.hotspot.accounting.root.RootShell
import kotlinx.coroutines.delay

/**
 * One client's cumulative traffic as reported by the kernel, in bytes.
 *
 * Counters are monotonic while the accounting rules live; consumers diff them. See
 * [CounterManager] for delta tracking.
 */
data class DeviceTraffic(
    val mac: String,
    /** Bytes the client sent (client -> internet). */
    val txBytes: Long,
    /** Bytes the client received (internet -> client). */
    val rxBytes: Long,
) {
    val total: Long get() = txBytes + rxBytes
}

data class VerifyResult(
    val bytesBefore: Long,
    val bytesAfter: Long,
    val deviceCount: Int,
    val notes: List<String>,
) {
    val counting: Boolean get() = bytesAfter > bytesBefore
    val delta: Long get() = bytesAfter - bytesBefore
}

/**
 * A per-MAC traffic counter installed in the kernel.
 *
 * Two implementations exist because Android's networking stack differs across kernels:
 *  - [NftBridgeBackend] counts at layer 2 via nftables' `bridge` family. Preferred.
 *  - [IptablesBackend] is the legacy fallback for kernels built without bridge netfilter.
 */
interface CounterBackend {
    val name: String
    suspend fun isSupported(): Boolean
    suspend fun ensureInstalled(): Boolean
    suspend fun addDevice(mac: String): Boolean
    suspend fun removeDevice(mac: String): Boolean
    suspend fun readCounters(): List<DeviceTraffic>
    suspend fun uninstall(): Boolean
    suspend fun verifyCounting(seconds: Int = 5): VerifyResult
}

internal val MAC_PATTERN = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

internal fun requireValidMac(mac: String): String {
    val normalized = mac.trim().lowercase()
    require(MAC_PATTERN.matches(normalized)) { "invalid MAC address: $mac" }
    return normalized
}

/** Parses "packets bytes" pairs and MAC tags out of nft/iptables textual output. */
internal object RuleParsing {

    /** `counter packets 12 bytes 3456` */
    private val COUNTER_RE = Regex("""counter\s+packets\s+(\d+)\s+bytes\s+(\d+)""")

    /** Our rule comments: `hsacc:up:aa:bb:cc:dd:ee:ff` / `hsacc:down:...` */
    private val TAG_RE = Regex("""hsacc:(up|down):([0-9a-fA-F:]{17})""")

    /** `# handle 42` (nft) — used for deletion. */
    private val HANDLE_RE = Regex("""#\s*handle\s+(\d+)""")

    data class ParsedRule(
        val mac: String,
        val direction: Direction,
        val bytes: Long,
        val packets: Long,
        val handle: String?,
    )

    enum class Direction { UP, DOWN }

    /**
     * Extracts every one of our counter rules from a ruleset dump.
     *
     * Our rules are always emitted on a single line by `nft -a list chain`, so per-line parsing is
     * safe and avoids depending on `nft -j`, which some Android builds omit.
     */
    fun parseNftRules(dump: String): List<ParsedRule> {
        val out = mutableListOf<ParsedRule>()
        for (line in dump.lineSequence()) {
            if (!line.contains("hsacc:")) continue
            val tag = TAG_RE.find(line) ?: continue
            val counter = COUNTER_RE.find(line) ?: continue
            val direction = if (tag.groupValues[1] == "up") Direction.UP else Direction.DOWN
            out += ParsedRule(
                mac = tag.groupValues[2].lowercase(),
                direction = direction,
                packets = counter.groupValues[1].toLongOrNull() ?: 0L,
                bytes = counter.groupValues[2].toLongOrNull() ?: 0L,
                handle = HANDLE_RE.find(line)?.groupValues?.get(1),
            )
        }
        return out
    }

    /** Collapses per-direction rules into one row per device. */
    fun toDeviceTraffic(rules: List<ParsedRule>): List<DeviceTraffic> =
        rules.groupBy { it.mac }.map { (mac, rs) ->
            DeviceTraffic(
                mac = mac,
                txBytes = rs.filter { it.direction == Direction.UP }.sumOf { it.bytes },
                rxBytes = rs.filter { it.direction == Direction.DOWN }.sumOf { it.bytes },
            )
        }
}

/**
 * nftables backend counting at layer 2 in the `bridge` family.
 *
 * For each client we install **two** rules, because a single hook only ever sees one direction:
 *
 * ```
 * table bridge hsacc {
 *   chain prerouting {
 *     type filter hook prerouting priority -300; policy accept;
 *     ether saddr <mac> counter comment "hsacc:up:<mac>"     # client -> internet
 *     ether daddr <mac> counter comment "hsacc:down:<mac>"   # internet -> client
 *   }
 * }
 * ```
 *
 * Why the bridge family rather than `ip filter forward`:
 *  - Hotspot traffic is *forwarded*, so local output counters would see nothing at all.
 *  - At layer 2 both directions are visible with the client's real MAC on one side, and the
 *    per-packet view is unaffected by source NAT (which rewrites IPs but never the ethernet
 *    header of a forwarded frame in either direction).
 *  - Layer 2 identity survives DHCP: a client that renews into a different IP keeps its counter,
 *    which is precisely what per-device billing needs.
 *
 * `priority -300` runs before br_netfilter's defrag handling, so frames the IP stack would drop
 * (fragments, non-IP protocols) are still counted for the client.
 */
class NftBridgeBackend : CounterBackend {

    override val name = "nftables bridge (per-MAC, both directions)"

    private val table = TABLE

    @Volatile private var supportChecked = false
    @Volatile private var supported = false
    @Volatile private var nft: String? = null

    override suspend fun isSupported(): Boolean {
        if (supportChecked) return supported
        // `detect()` suspends (it drives the root shell), and Kotlin forbids a suspension point
        // inside a `synchronized` block. The probe is therefore run outside the lock; the only cost
        // of a concurrent duplicate probe is one redundant `nft` invocation, and the result is
        // idempotent, so this needs no extra mutex.
        val detected = detect()
        synchronized(this) {
            if (!supportChecked) {
                supported = detected
                supportChecked = true
            }
            return supported
        }
    }

    private suspend fun detect(): Boolean {
        val bin = RootShell.which("nft") ?: run {
            Log.w(TAG, "nft binary not found")
            return false
        }
        nft = bin

        // Having the binary is not enough: CONFIG_NF_TABLES_BRIDGE must be built in. Probe for real.
        RootShell.exec("$bin delete table bridge $PROBE_TABLE 2>/dev/null")
        val res = RootShell.exec(
            "$bin 'add table bridge $PROBE_TABLE; " +
                "add chain bridge $PROBE_TABLE c { type filter hook prerouting priority -300; policy accept; }' 2>&1"
        )
        RootShell.exec("$bin delete table bridge $PROBE_TABLE 2>/dev/null")
        if (!res.ok) {
            Log.w(TAG, "bridge family unsupported: ${res.stdout}${res.stderr}")
        }
        return res.ok
    }

    override suspend fun ensureInstalled(): Boolean {
        val bin = nft ?: RootShell.which("nft") ?: return false
        nft = bin
        // Recreate from scratch: idempotent, and drops counters from a previous process lifetime
        // whose baselines we no longer hold.
        RootShell.exec("$bin delete table bridge $table 2>/dev/null")
        val res = RootShell.exec(
            "$bin 'add table bridge $table; " +
                "add chain bridge $table $CHAIN { type filter hook prerouting priority -300; policy accept; }' 2>&1"
        )
        if (!res.ok) Log.e(TAG, "ensureInstalled failed: ${res.stdout}${res.stderr}")
        return res.ok
    }

    override suspend fun addDevice(mac: String): Boolean {
        val m = requireValidMac(mac)
        val bin = nft ?: return false
        // Both directions in one nft invocation to halve the round trips.
        val script = buildString {
            append("add rule bridge $table $CHAIN ether saddr $m counter comment \"${upTag(m)}\"; ")
            append("add rule bridge $table $CHAIN ether daddr $m counter comment \"${downTag(m)}\"")
        }
        val res = RootShell.exec("$bin '$script' 2>&1")
        if (!res.ok) Log.w(TAG, "addDevice($m) failed: ${res.stdout}${res.stderr}")
        return res.ok
    }

    override suspend fun removeDevice(mac: String): Boolean {
        val m = requireValidMac(mac)
        val bin = nft ?: return false
        val handles = listRules().filter { it.mac == m }.mapNotNull { it.handle }
        if (handles.isEmpty()) return true
        val deletes = handles.joinToString("; ") { "delete rule bridge $table $CHAIN handle $it" }
        val res = RootShell.exec("$bin '$deletes' 2>&1")
        return res.ok
    }

    override suspend fun readCounters(): List<DeviceTraffic> =
        RuleParsing.toDeviceTraffic(listRules())

    override suspend fun uninstall(): Boolean {
        val bin = nft ?: return false
        RootShell.exec("$bin delete table bridge $table 2>/dev/null")
        return true
    }

    override suspend fun verifyCounting(seconds: Int): VerifyResult {
        val notes = mutableListOf<String>()
        val before = readCounters()
        val interfaces = tetherInterfaces()
        notes += if (interfaces.isEmpty()) {
            "未识别到热点接口，将统计网桥上的全部帧"
        } else {
            "热点接口: ${interfaces.joinToString()}"
        }
        notes += "已安装 ${before.size} 台设备的计数器，开始采样 ${seconds}s…"
        delay(seconds * 1000L)
        val after = readCounters()
        val delta = after.sumOf { it.total } - before.sumOf { it.total }
        notes += if (delta > 0) {
            "计数正常：采样窗口内累计 +$delta 字节"
        } else {
            "计数未增长：请确认有设备已连接热点并正在传输数据"
        }
        if (after.isEmpty()) {
            notes += "当前没有任何设备计数器，请先在实时页面发现设备"
        }
        return VerifyResult(
            bytesBefore = before.sumOf { it.total },
            bytesAfter = after.sumOf { it.total },
            deviceCount = after.size,
            notes = notes,
        )
    }

    private suspend fun listRules(): List<RuleParsing.ParsedRule> {
        val bin = nft ?: return emptyList()
        val dump = RootShell.execOrNull("$bin -a list chain bridge $table $CHAIN 2>/dev/null")
            ?: return emptyList()
        return RuleParsing.parseNftRules(dump)
    }

    private suspend fun tetherInterfaces(): List<String> {
        val out = RootShell.execOrNull("ip -o link show 2>/dev/null") ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val name = line.substringAfter(':').trim().substringBefore('@').substringBefore(':').trim()
            if (name.isEmpty()) null
            else if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
                name.startsWith("wlan1") || name.startsWith("rndis") || name.startsWith("bt-pan") ||
                name.startsWith("wlan2")
            ) name else null
        }.distinct().toList()
    }

    companion object {
        private const val TAG = "NftBridge"
        const val TABLE = "hsacc"
        const val CHAIN = "prerouting"
        private const val PROBE_TABLE = "hsaccprobe"

        fun upTag(mac: String) = "hsacc:up:$mac"
        fun downTag(mac: String) = "hsacc:down:$mac"
    }
}

/**
 * Legacy backend for kernels without bridge-family nftables.
 *
 * Installs a dedicated chain at the *head* of `filter FORWARD`, so our counters see packets before
 * Android's own tethering rules accept them and skip the remainder of the chain.
 *
 * Known limitation: a client's traffic is counted once per direction through FORWARD, so this
 * backend attributes everything it sees to upload. It exists to keep the app usable on old kernels,
 * not to be as accurate as the nftables path.
 */
class IptablesBackend : CounterBackend {

    override val name = "iptables FORWARD (legacy)"

    @Volatile private var ipt: String? = null

    override suspend fun isSupported(): Boolean {
        val bin = RootShell.which("iptables") ?: return false
        ipt = bin
        val res = RootShell.exec("$bin -t filter -S FORWARD >/dev/null 2>&1; echo rc=\$?")
        return res.trimmed.endsWith("rc=0")
    }

    override suspend fun ensureInstalled(): Boolean {
        val bin = ipt ?: RootShell.which("iptables") ?: return false
        ipt = bin
        RootShell.exec("$bin -t filter -N $CHAIN 2>/dev/null")
        RootShell.exec("$bin -t filter -F $CHAIN 2>/dev/null")
        // Exactly one jump, at position 1, so nothing upstream can shadow us.
        RootShell.exec("$bin -t filter -D FORWARD -j $CHAIN 2>/dev/null")
        val res = RootShell.exec("$bin -t filter -I FORWARD 1 -j $CHAIN 2>&1")
        if (!res.ok) Log.e(TAG, "ensureInstalled failed: ${res.stdout}${res.stderr}")
        return res.ok
    }

    override suspend fun addDevice(mac: String): Boolean {
        val m = requireValidMac(mac)
        val bin = ipt ?: return false
        // Two rules so downloads land on the RX side of the ledger. The chain is only traversed by
        // forwarded traffic, and RETURN keeps subsequent rules from being skipped.
        val res = RootShell.exec(
            "$bin -t filter -A $CHAIN -m mac --mac-source $m " +
                "-m comment --comment \"${NftBridgeBackend.upTag(m)}\" -j RETURN 2>&1; " +
                "$bin -t filter -A $CHAIN -m mac --mac-destination $m " +
                "-m comment --comment \"${NftBridgeBackend.downTag(m)}\" -j RETURN 2>&1"
        )
        return res.ok
    }

    override suspend fun removeDevice(mac: String): Boolean {
        val m = requireValidMac(mac)
        val bin = ipt ?: return false
        RootShell.exec(
            "$bin -t filter -D $CHAIN -m mac --mac-source $m " +
                "-m comment --comment \"${NftBridgeBackend.upTag(m)}\" -j RETURN 2>/dev/null; " +
                "$bin -t filter -D $CHAIN -m mac --mac-destination $m " +
                "-m comment --comment \"${NftBridgeBackend.downTag(m)}\" -j RETURN 2>/dev/null"
        )
        return true
    }

    override suspend fun readCounters(): List<DeviceTraffic> {
        val bin = ipt ?: return emptyList()
        val dump = RootShell.execOrNull("$bin -t filter -L $CHAIN -n -v -x 2>/dev/null")
            ?: return emptyList()
        return parseChain(dump)
    }

    override suspend fun uninstall(): Boolean {
        val bin = ipt ?: return false
        RootShell.exec("$bin -t filter -D FORWARD -j $CHAIN 2>/dev/null")
        RootShell.exec("$bin -t filter -F $CHAIN 2>/dev/null")
        RootShell.exec("$bin -t filter -X $CHAIN 2>/dev/null")
        return true
    }

    override suspend fun verifyCounting(seconds: Int): VerifyResult {
        val before = readCounters().sumOf { it.total }
        delay(seconds * 1000L)
        val counters = readCounters()
        val after = counters.sumOf { it.total }
        val notes = mutableListOf<String>()
        notes += if (after > before) "计数正常：采样窗口内 +${after - before} 字节"
        else "计数未增长：确认有客户端正在传输；若 FORWARD 被厂商规则提前 ACCEPT，此后端可能无法计数"
        return VerifyResult(before, after, counters.size, notes)
    }

    /**
     * Parses `iptables -L -v -x` rows. With `-x` the first two columns are exact packet and byte
     * counts, and our comment is echoed at the end of the line.
     */
    internal fun parseChain(output: String): List<DeviceTraffic> {
        val tx = mutableMapOf<String, Long>()
        val rx = mutableMapOf<String, Long>()
        for (line in output.lineSequence()) {
            if (!line.contains("hsacc:")) continue
            val tag = RuleParsing.run {
                // Reuse the shared tag regex via a direct match.
                Regex("""hsacc:(up|down):([0-9a-fA-F:]{17})""").find(line)
            } ?: continue
            val direction = tag.groupValues[1]
            val mac = tag.groupValues[2].lowercase()
            if (!MAC_PATTERN.matches(mac)) continue
            val cols = line.trim().split(Regex("\\s+"))
            val bytes = cols.getOrNull(1)?.toLongOrNull() ?: 0L
            if (direction == "up") tx[mac] = (tx[mac] ?: 0L) + bytes
            else rx[mac] = (rx[mac] ?: 0L) + bytes
        }
        return (tx.keys + rx.keys).map { mac ->
            DeviceTraffic(mac = mac, txBytes = tx[mac] ?: 0L, rxBytes = rx[mac] ?: 0L)
        }
    }

    private companion object {
        const val TAG = "IptablesBackend"
        const val CHAIN = "HSACC"
    }
}
