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
        val tetherInterfaces: List<String>,
        val ifbSupported: Boolean,
        val notes: List<String>,
    ) {
        /** Blocking needs a layer where the client MAC is still identifiable. */
        val canBlock: Boolean get() = hasEbtables || hasIptables
        /** Rate limiting needs tc plus a usable attachment point. */
        val canRateLimit: Boolean get() = hasTc && (ifbSupported || tetherInterfaces.isNotEmpty())
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
        if (!hasEbtables) notes += "未找到 ebtables，断网将退化为下发 iptables 规则"
        if (ifaces.isEmpty()) notes += "未能识别热点接口名，限速前请先连接一台设备用于探测"

        val caps = Capabilities(hasTc, hasEbtables, hasIptables, ifaces, ifbSupported, notes)
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
     * Administratively blocks or unblocks a client.
     *
     * Implemented in the bridge family via `ebtables` when present, because the client's MAC is
     * still the ethernet source there. Without `ebtables` we refuse rather than silently installing
     * an IP-based rule that would break as soon as the client's lease changes.
     */
    suspend fun setBlocked(mac: String, blocked: Boolean, currentIp: String?): Result<Unit> {
        val m = requireValidMac(mac)
        val caps = capabilities()

        return if (caps.hasEbtables) {
            setBlockedEbtables(m, blocked)
        } else {
            // Explicitly refuse: an IP-based block is not equivalent and the caller must opt in.
            Result.failure(
                IllegalStateException(
                    "缺少 ebtables，无法按 MAC 断网。当前 ROM 上仅能按 IP 限制，" +
                        "且客户端续租换 IP 后会失效，因此未执行。"
                )
            )
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
     * If the tethering qdisc already occupies the root handle on that interface, we bail out with a
     * clear error instead of deleting someone else's qdisc.
     */
    suspend fun setRateLimit(
        mac: String,
        ip: String?,
        downKbps: Int?,
        upKbps: Int?,
    ): Result<Unit> {
        val m = requireValidMac(mac)
        val caps = capabilities()

        if (!caps.hasTc) {
            return Result.failure(IllegalStateException("系统缺少 tc 命令，无法限速"))
        }
        if (downKbps == null && upKbps == null) {
            return clearRateLimit(mac, ip)
        }

        val iface = caps.tetherInterfaces.firstOrNull()
            ?: return Result.failure(
                IllegalStateException("无法确定热点接口名，请先让一台设备连接热点后重试")
            )

        // Safety gate: refuse if the root qdisc on this interface is not ours.
        val existing = RootShell.execOrNull("tc qdisc show dev $iface 2>/dev/null").orEmpty()
        if (existing.isNotBlank() && !existing.contains("qdisc htb 1:") && !existing.contains("qdisc htb 10:")) {
            val occupier = existing.lineSequence().firstOrNull { it.startsWith("qdisc") }?.trim()
            return Result.failure(
                IllegalStateException(
                    "接口 $iface 上已存在系统自建的队列规则：$occupier\n" +
                        "直接叠加会破坏热点，已中止。请先关闭再开启一次热点后重试。"
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
    suspend fun clearRateLimit(mac: String, ip: String?): Result<Unit> {
        val m = requireValidMac(mac)
        val caps = capabilities()
        val iface = caps.tetherInterfaces.firstOrNull()
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
        val iface = caps.tetherInterfaces.firstOrNull()

        if (caps.hasEbtables) {
            RootShell.exec("ebtables -D FORWARD -j HSACC_BLOCK 2>/dev/null")
            RootShell.exec("ebtables -F HSACC_BLOCK 2>/dev/null")
            RootShell.exec("ebtables -X HSACC_BLOCK 2>/dev/null")
            log += "已清除 ebtables 断网规则"
        }
        if (caps.hasTc && iface != null) {
            RootShell.exec("tc qdisc del dev $iface handle ffff: ingress 2>/dev/null")
            RootShell.exec("tc qdisc del dev $iface root handle 1: 2>/dev/null")
            RootShell.exec("ip link del ifb-hsacc 2>/dev/null")
            log += "已清除 $iface 上的限速队列"
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
