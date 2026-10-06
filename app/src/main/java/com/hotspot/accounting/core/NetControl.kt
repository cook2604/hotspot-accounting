package com.hotspot.accounting.core

import android.util.Log
import com.hotspot.accounting.root.RootShell

/**
 * Applies and reverts per-device network controls (blocking and rate limiting).
 *
 * Design stance, stated plainly because these operations touch the live network path:
 *
 *  - Android's tethering stack owns the HTB qdisc on the hotspot interface. Attaching our own
 *    classes to *that* qdisc is how you break the hotspot for every client at once. So we never
 *    touch it. Rate limiting instead attaches to a dedicated, secondary path (the client interface
 *    when it is distinct, otherwise an IFB mirror), and always inside a root qdisc we created and
 *    can delete wholesale.
 *  - Every operation records exactly what it created. [revertAll] removes those objects, so a
 *    failure mid-way cannot leave the device in a half-configured state.
 *  - A user who is blocked is documented as an administrative action; the block is implemented at
 *    the bridge layer when available, because that is where the client MAC is still the source
 *    address. Falling back to an IP-based FORWARD drop is possible but is *not* done silently —
 *    see [NetControlCapabilities].
 *
 * The app surfaces whether rate limiting is even applicable, rather than appearing to succeed.
 */
class NetControl {

    /** What this device can actually do, so the UI can avoid offering broken switches. */
    data class Capabilities(
        val hasTc: Boolean,
        val hasEbtables: Boolean,
        val hasIptables: Boolean,
        /**
         * True when `nft` exists AND its bridge family works on this kernel.
         *
         * This is the preferred blocking mechanism. It is not merely an ebtables substitute: the
         * bridge family sees the client's original MAC, which is the property blocking depends on.
         */
        val hasNftBridge: Boolean,
        /**
         * Every tethering interface present, with its transport.
         *
         * A phone commonly offers Wi-Fi *and* USB tethering simultaneously, so a single interface is
         * not enough: rate limiting has to act on the one the target client is actually on.
         */
        val tetherInterfaces: List<DeviceDiscovery.TetherInterface>,
        val ifbSupported: Boolean,
        val notes: List<String>,
    ) {
        /**
         * Blocking needs a layer where the client MAC is still identifiable.
         *
         * `ebtables` was originally treated as the only option, which wrongly disabled blocking on
         * ROMs that ship nftables but not ebtables — including the device this app was built for,
         * where counting already worked through the nft bridge family. Any of the three is capable;
         * the IP-based one is last because a DHCP renewal defeats it.
         */
        val canBlock: Boolean get() = hasNftBridge || hasEbtables || hasIptables

        /** Identifies the mechanism actually used, for the diagnostics panel. */
        val blockMechanism: String
            get() = when {
                hasNftBridge -> "nftables bridge（按 MAC）"
                hasEbtables -> "ebtables（按 MAC）"
                hasIptables -> "iptables FORWARD（按 IP，续租会失效）"
                else -> "不可用"
            }

        /** Rate limiting needs tc plus at least one usable attachment point. */
        val canRateLimit: Boolean get() = hasTc && (ifbSupported || tetherInterfaces.isNotEmpty())

        /** Comma-separated "name (transport)" summary for the diagnostics panel. */
        val interfaceSummary: String
            get() = if (tetherInterfaces.isEmpty()) "未识别"
            else tetherInterfaces.joinToString { "${it.name}（${it.transport.label}）" }
    }

    @Volatile
    private var capabilities: Capabilities? = null

    /** Rule objects we created, so teardown is exact rather than best-effort. */
    private val appliedBlocks = linkedSetOf<String>()
    private val appliedRates = linkedSetOf<String>()

    suspend fun capabilities(): Capabilities {
        capabilities?.let { return it }
        val hasTc = RootShell.which("tc") != null
        val hasEbtables = RootShell.which("ebtables") != null
        val hasIptables = RootShell.which("iptables") != null
        val ifaces = DeviceDiscovery.tetherInterfaces()
        val notes = mutableListOf<String>()

        // Bridge-family nftables is the preferred blocking layer. Detect it for real rather than
        // assuming, because the binary can exist on a kernel built without CONFIG_NF_TABLES_BRIDGE.
        val hasNftBridge = probeNftBridge()
        if (!hasNftBridge && !hasEbtables) {
            notes += "nftables bridge 与 ebtables 都不可用，断网只能按 IP 下发，客户端换 IP 后会失效"
        } else if (hasNftBridge) {
            notes += "断网使用 nftables bridge，按 MAC 拦截，客户端换 IP 不受影响"
        }

        // IFB lets us shape inbound traffic without touching the tethering qdisc.
        var ifbSupported = false
        if (hasTc) {
            ifbSupported = RootShell.execOrNull("ls /sys/module/ifb 2>/dev/null") != null ||
                RootShell.execOrNull("modprobe -n ifb 2>/dev/null; echo ok") != null
            if (!ifbSupported) {
                notes += "内核可能没有 ifb 模块，下载限速将尝试直接作用于客户端接口"
            }
        }
        if (!hasTc) notes += "未找到 tc 命令，限速功能不可用"
        if (ifaces.isEmpty()) notes += "未能识别共享接口名，限速前请先连接一台设备用于探测"

        // Point out multi-transport setups explicitly: this is the case where using the wrong
        // interface silently does nothing, so the user should know both are visible.
        val transports = ifaces.map { it.transport }.distinct()
        if (transports.size > 1) {
            notes += "检测到多种共享方式（${transports.joinToString { it.label }}），限速会按设备所在接口分别生效"
        }
        if (transports.contains(DeviceDiscovery.Transport.USB)) {
            notes += "USB 共享的客户端可达；USB 网卡抓包在二层，按 MAC 计数同样有效"
        }

        val caps = Capabilities(hasTc, hasEbtables, hasIptables, hasNftBridge, ifaces, ifbSupported, notes)
        capabilities = caps
        return caps
    }

    fun invalidateCapabilities() {
        capabilities = null
    }

    // ---------------------------------------------------------------------------------------
    // Blocking
    // ---------------------------------------------------------------------------------------

    /**
     * Detects whether `nft` can create a bridge-family chain.
     *
     * Written because an earlier version of this class assumed `ebtables` was the only way to filter
     * by MAC, and consequently refused to block at all on the target device. That ROM has no
     * ebtables but does have nftables with bridge support — which the counter backend was already
     * using — so blocking was being denied despite a perfectly capable mechanism being available.
     */
    private suspend fun probeNftBridge(): Boolean {
        val nft = RootShell.which("nft") ?: return false
        val probeTable = "hsacc_blockprobe"
        RootShell.exec("$nft delete table bridge $probeTable 2>/dev/null")
        val ok = RootShell.exec(
            "$nft 'add table bridge $probeTable; " +
                "add chain bridge $probeTable c { type filter hook prerouting priority -250; policy accept; }' 2>&1"
        ).ok
        RootShell.exec("$nft delete table bridge $probeTable 2>/dev/null")
        return ok
    }

    /**
     * Administratively blocks or unblocks a client.
     *
     * Preference order, and why:
     *  1. **nftables bridge** — sees the client's original MAC and is filtered before the IP stack,
     *     so a DHCP renewal cannot evade it. Already proven present, since the counter backend runs
     *     in the same family.
     *  2. **ebtables** — equivalent semantics, kept for kernels built without nft bridge support.
     *  3. **iptables** — last resort. It can only match an IP address, so the block silently stops
     *     applying the moment the client renews its lease. Used only when nothing else exists, and
     *     reported as such rather than pretending it is equivalent.
     */
    suspend fun setBlocked(mac: String, blocked: Boolean, currentIp: String?): Result<Unit> {
        val m = requireValidMac(mac)
        val caps = capabilities()

        return when {
            caps.hasNftBridge -> setBlockedNft(m, blocked)
            caps.hasEbtables -> setBlockedEbtables(m, blocked)
            caps.hasIptables -> setBlockedIptables(m, blocked, currentIp)
            else -> Result.failure(
                IllegalStateException(
                    "当前 ROM 上 nftables bridge、ebtables、iptables 都不可用，无法执行断网。"
                )
            )
        }
    }

    /**
     * Blocks by MAC using nftables in the bridge family.
     *
     * Both directions are needed for the same reason the counters need two rules: one hook sees a
     * frame only once, so `ether saddr` stops the client's outbound traffic while `ether daddr`
     * stops anything already on its way in.
     */
    private suspend fun setBlockedNft(mac: String, blocked: Boolean): Result<Unit> {
        val nft = RootShell.which("nft") ?: return Result.failure(IllegalStateException("未找到 nft"))
        val table = "hsacc_block"
        val chain = "prerouting"

        return try {
            if (blocked) {
                // Chain is created once and left in place; removing the table would also drop the
                // rules of any other device that is currently blocked.
                val install = RootShell.exec(
                    "$nft 'add table bridge $table; " +
                        "add chain bridge $table $chain " +
                        "{ type filter hook prerouting priority -250; policy accept; }' 2>&1"
                )
                if (!install.ok && !install.stdout.contains("File exists")) {
                    throw IllegalStateException(install.stdout + install.stderr)
                }
                // Comment tags make the rules identifiable for idempotent removal.
                val add = RootShell.exec(
                    "$nft 'add rule bridge $table $chain ether saddr $mac " +
                        "counter comment \"hsacc:block:$mac\" drop; " +
                        "add rule bridge $table $chain ether daddr $mac " +
                        "counter comment \"hsacc:block:$mac\" drop' 2>&1"
                )
                if (!add.ok) throw IllegalStateException(add.stdout + add.stderr)
                appliedBlocks += mac
            } else {
                // Delete by handle so we remove exactly our rules and nothing else.
                val handles = findBlockRuleHandles(mac)
                if (handles.isNotEmpty()) {
                    val deletes = handles.joinToString("; ") {
                        "delete rule bridge $table $chain handle $it"
                    }
                    RootShell.exec("$nft '$deletes' 2>&1")
                }
                appliedBlocks -= mac
                // Drop the table only when nothing remains blocked, so the ruleset is left clean.
                if (appliedBlocks.isEmpty()) {
                    RootShell.exec("$nft delete table bridge $table 2>/dev/null")
                }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "setBlockedNft($mac,$blocked) failed: ${t.message}")
            Result.failure(t)
        }
    }

    /** Finds the nft rule handles belonging to [mac] in the blocking chain. */
    private suspend fun findBlockRuleHandles(mac: String): List<String> {
        val nft = RootShell.which("nft") ?: return emptyList()
        val dump = RootShell.execOrNull(
            "$nft -a list chain bridge hsacc_block prerouting 2>/dev/null"
        ).orEmpty()
        val handles = mutableListOf<String>()
        for (line in dump.lineSequence()) {
            if (!line.contains("hsacc:block:$mac")) continue
            Regex("""#\s*handle\s+(\d+)""").find(line)?.groupValues?.get(1)?.let { handles += it }
        }
        return handles
    }

    /**
     * Last-resort block by IP address.
     *
     * Documented as lossy at the call site: an iptables rule cannot express "this MAC", so the block
     * lasts only until the client's DHCP lease changes.
     */
    private suspend fun setBlockedIptables(mac: String, blocked: Boolean, ip: String?): Result<Unit> {
        val ipt = RootShell.which("iptables")
            ?: return Result.failure(IllegalStateException("未找到 iptables"))
        if (ip.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("该设备当前没有 IP，按 IP 断网无法执行；请等它重新连接后重试。")
            )
        }
        return try {
            if (blocked) {
                RootShell.exec(
                    "$ipt -I FORWARD 1 -s $ip -m comment --comment \"hsacc:block:$mac\" -j DROP 2>&1"
                )
                RootShell.exec(
                    "$ipt -I FORWARD 1 -d $ip -m comment --comment \"hsacc:block:$mac\" -j DROP 2>&1"
                )
                appliedBlocks += mac
            } else {
                repeat(2) {
                    RootShell.exec(
                        "$ipt -D FORWARD -s $ip -m comment --comment \"hsacc:block:$mac\" -j DROP 2>/dev/null"
                    )
                    RootShell.exec(
                        "$ipt -D FORWARD -d $ip -m comment --comment \"hsacc:block:$mac\" -j DROP 2>/dev/null"
                    )
                }
                appliedBlocks -= mac
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun setBlockedEbtables(mac: String, blocked: Boolean): Result<Unit> {
        val chain = "HSACC_BLOCK"
        return try {
            // Two rules: one per direction. FORWARD sits in the bridge family and sees the frame
            // with the client MAC intact on the matching side.
            if (blocked) {
                RootShell.exec("ebtables -N $chain 2>/dev/null")
                RootShell.exec("ebtables -F $chain 2>/dev/null")
                // Idempotent: drop any previous jump before re-adding, so we never stack jumps.
                RootShell.exec("ebtables -D FORWARD -j $chain 2>/dev/null")
                RootShell.exec("ebtables -I FORWARD 1 -j $chain 2>&1")
                val add = RootShell.exec(
                    "ebtables -A $chain -s $mac -j DROP 2>&1; " +
                        "ebtables -A $chain -d $mac -j DROP 2>&1"
                )
                if (!add.ok) throw IllegalStateException(add.stdout + add.stderr)
                appliedBlocks += mac
            } else {
                RootShell.exec("ebtables -D $chain -s $mac -j DROP 2>/dev/null")
                RootShell.exec("ebtables -D $chain -d $mac -j DROP 2>/dev/null")
                appliedBlocks -= mac
                // If no device is blocked any more, remove the jump so FORWARD stays untouched.
                if (appliedBlocks.isEmpty()) {
                    RootShell.exec("ebtables -D FORWARD -j $chain 2>/dev/null")
                }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "setBlocked($mac,$blocked) failed: ${t.message}")
            Result.failure(t)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rate limiting
    // ---------------------------------------------------------------------------------------

    /**
     * Applies or clears a per-device rate cap.
     *
     * Download is shaped on the *client-facing* interface using an HTB class keyed by the client's
     * IP. Upload is shaped with an ingress police filter on the same interface. Both live in a root
     * qdisc we own (`1:`), never in the tethering stack's.
     *
     * The interface is chosen per client (see [ifaceHint]) because a phone can tether over Wi-Fi and
     * USB at the same time, and a rule applied to the wrong link silently does nothing.
     *
     * If the tethering qdisc already occupies the root handle on that interface, we bail out with a
     * clear error instead of deleting someone else's qdisc.
     */
    suspend fun setRateLimit(
        mac: String,
        ip: String?,
        downKbps: Int?,
        upKbps: Int?,
        /**
         * Interface the client is on. When null, it is resolved from [ip] via the neighbour table.
         *
         * Previously this always took the *first* tether interface, which meant a machine on USB
         * tethering would have its rate applied to the Wi-Fi AP — either doing nothing for that client
         * or throttling unrelated Wi-Fi clients. Resolving per client is what makes USB tethering
         * work correctly alongside a Wi-Fi hotspot.
         */
        ifaceHint: String? = null,
    ): Result<Unit> {
        val m = requireValidMac(mac)
        val caps = capabilities()

        if (!caps.hasTc) {
            return Result.failure(IllegalStateException("系统缺少 tc 命令，无法限速"))
        }

        // Resolve the target interface: explicit hint, then the neighbour table, then a single
        // unambiguous tether interface if there is exactly one.
        val resolved: String? = ifaceHint?.takeIf { it.isNotBlank() }
            ?: ip?.let { DeviceDiscovery.interfaceForAddress(it) }
            ?: caps.tetherInterfaces.singleOrNull()?.name

        if (downKbps == null && upKbps == null) {
            // Clearing: hand the resolved interface through so the limit is removed from the link it
            // was actually applied to.
            return clearRateLimit(mac, ip, resolved)
        }

        val iface = resolved
            ?: return Result.failure(
                IllegalStateException(
                    "无法确定该设备所在的网络接口（可能同时开启了多个共享方式）。\n" +
                        "请确认设备仍在线后重试；当前已识别接口：" + caps.interfaceSummary
                )
            )

        // Safety gate: refuse if the root qdisc on this interface is not ours.
        val existing = RootShell.execOrNull("tc qdisc show dev $iface 2>/dev/null").orEmpty()
        if (existing.isNotBlank() && !existing.contains("qdisc htb 1:") && !existing.contains("qdisc htb 10:")) {
            val occupier = existing.lineSequence().firstOrNull { it.startsWith("qdisc") }?.trim()
            return Result.failure(
                IllegalStateException(
                    "接口 $iface 上已存在系统自建的队列规则：$occupier\n" +
                        "直接叠加会破坏该共享方式，已中止。请先关闭再开启一次共享后重试。"
                )
            )
        }

        return try {
            RootShell.exec("tc qdisc add dev $iface root handle 1: htb default 9999 2>&1")
            RootShell.exec("tc class add dev $iface parent 1: classid 1:1 htb rate 1000mbit 2>&1")

            if (ip != null && downKbps != null) {
                val classId = classIdFor(m)
                val ceil = downKbps
                RootShell.exec(
                    "tc class replace dev $iface parent 1:1 classid $classId htb " +
                        "rate ${downKbps}kbit ceil ${ceil}kbit burst 64k 2>&1"
                )
                RootShell.exec(
                    "tc qdisc replace dev $iface parent $classId handle ${handleFor(m)}: " +
                        "fq_codel 2>&1"
                )
                // Route this client's traffic into its class.
                RootShell.exec(
                    "tc filter replace dev $iface protocol ip parent 1:0 prio 1 u32 " +
                        "match ip dst $ip/32 flowid $classId 2>&1"
                )
                appliedRates += m
            }

            if (upKbps != null) {
                // Ingress shaping needs an IFB mirror; without it, report rather than pretend.
                if (caps.ifbSupported) {
                    val direction = applyIngressShape(iface, m, ip, upKbps)
                    if (direction.isFailure) return direction
                    appliedRates += m
                } else {
                    return Result.failure(
                        IllegalStateException("内核不支持 ifb，无法限制上传速度（下载限速已生效）")
                    )
                }
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "setRateLimit($m) failed: ${t.message}")
            // Roll back anything we managed to create so the client is not left shaped oddly.
            runCatching { clearRateLimit(m, ip) }
            Result.failure(t)
        }
    }

    private suspend fun applyIngressShape(
        iface: String,
        mac: String,
        ip: String?,
        upKbps: Int,
    ): Result<Unit> {
        if (ip == null) {
            return Result.failure(IllegalStateException("设备尚无 IP，无法限制上传速度"))
        }
        val ifb = "ifb-hsacc"
        RootShell.exec("ip link add $ifb type ifb 2>/dev/null")
        RootShell.exec("ip link set $ifb up 2>/dev/null")

        // Mirror the client's traffic into the IFB device, then shape there.
        RootShell.exec(
            "tc qdisc add dev $iface handle ffff: ingress 2>/dev/null"
        )
        RootShell.exec(
            "tc filter add dev $iface parent ffff: protocol ip prio 1 u32 " +
                "match ip src $ip/32 action mirred egress redirect dev $ifb 2>&1"
        )
        RootShell.exec("tc qdisc add dev $ifb root handle 2: htb default 9999 2>&1")
        RootShell.exec("tc class add dev $ifb parent 2: classid 2:1 htb rate 1000mbit 2>&1")
        val cls = classIdFor(mac)
        RootShell.exec(
            "tc class replace dev $ifb parent 2:1 classid $cls htb " +
                "rate ${upKbps}kbit ceil ${upKbps}kbit burst 64k 2>&1"
        )
        RootShell.exec(
            "tc filter replace dev $ifb protocol ip parent 2:0 prio 1 u32 " +
                "match ip src $ip/32 flowid $cls 2>&1"
        )
        return Result.success(Unit)
    }

    /** Removes every rate-limit object belonging to [mac]. */
    suspend fun clearRateLimit(mac: String, ip: String?, ifaceHint: String? = null): Result<Unit> {
        val m = requireValidMac(mac)
        val caps = capabilities()

        // Same resolution order as setRateLimit, so a limit applied on rndis0 is cleared from rndis0.
        val iface = ifaceHint?.takeIf { it.isNotBlank() }
            ?: ip?.let { DeviceDiscovery.interfaceForAddress(it) }
            ?: caps.tetherInterfaces.singleOrNull()?.name
            ?: caps.tetherInterfaces.firstOrNull()?.name

        if (iface != null) {
            if (ip != null) {
                RootShell.exec(
                    "tc filter del dev $iface protocol ip parent 1:0 prio 1 u32 " +
                        "match ip dst $ip/32 2>/dev/null"
                )
                RootShell.exec(
                    "tc filter del dev $iface parent ffff: protocol ip prio 1 u32 " +
                        "match ip src $ip/32 2>/dev/null"
                )
            }
            RootShell.exec("tc class del dev $iface parent 1:1 classid ${classIdFor(m)} 2>/dev/null")
        }
        appliedRates -= m

        // Tear down the ingress mirror only when nothing else is using it.
        if (appliedRates.isEmpty() && iface != null) {
            RootShell.exec("tc qdisc del dev $iface handle ffff: ingress 2>/dev/null")
            RootShell.exec("tc qdisc del dev $iface root handle 1: 2>/dev/null")
            RootShell.exec("ip link del ifb-hsacc 2>/dev/null")
        }
        return Result.success(Unit)
    }

    /**
     * Removes everything this class created. Safe to call at any time, including when nothing was
     * applied — it is the app's "undo everything" escape hatch.
     */
    suspend fun revertAll(): List<String> {
        val log = mutableListOf<String>()
        val caps = capabilities()

        // nftables blocking table. Dropping the whole table removes every drop rule at once, which is
        // exactly the intent here.
        if (caps.hasNftBridge) {
            RootShell.exec("nft delete table bridge hsacc_block 2>/dev/null")
            log += "已清除 nftables bridge 断网规则"
        }
        if (caps.hasEbtables) {
            RootShell.exec("ebtables -D FORWARD -j HSACC_BLOCK 2>/dev/null")
            RootShell.exec("ebtables -F HSACC_BLOCK 2>/dev/null")
            RootShell.exec("ebtables -X HSACC_BLOCK 2>/dev/null")
            log += "已清除 ebtables 断网规则"
        }
        if (caps.hasIptables) {
            // Comment-tagged rules from the last-resort IP path. Handles are unknown, so the rules
            // are removed by repeating the delete until it stops matching.
            RootShell.exec(
                "for i in 1 2 3 4; do iptables -D FORWARD -m comment --comment " +
                    "\"hsacc:block\" -j DROP 2>/dev/null || break; done"
            )
        }
        // Clean every tether interface, not just one: with Wi-Fi and USB tethering both active, a
        // qdisc could have been created on either, and leaving one behind would keep throttling.
        if (caps.hasTc && caps.tetherInterfaces.isNotEmpty()) {
            for (t in caps.tetherInterfaces) {
                RootShell.exec("tc qdisc del dev ${t.name} handle ffff: ingress 2>/dev/null")
                RootShell.exec("tc qdisc del dev ${t.name} root handle 1: 2>/dev/null")
            }
            RootShell.exec("ip link del ifb-hsacc 2>/dev/null")
            log += "已清除限速队列：" + caps.interfaceSummary
        }
        appliedBlocks.clear()
        appliedRates.clear()
        return log
    }

    /** Stable HTB class id per MAC, so repeated applies replace rather than accumulate. */
    private fun classIdFor(mac: String): String {
        // Hash the MAC into the 0x1000..0xFFFF range, avoiding 1 and 0x9999 (the default class).
        val h = mac.filter { it.isLetterOrDigit() }.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        val minor = 0x1000 + (h % 0x8000)
        return "1:${Integer.toHexString(minor)}"
    }

    private fun handleFor(mac: String): String = classIdFor(mac).substringAfter(':')

    private companion object {
        const val TAG = "NetControl"
    }
}
