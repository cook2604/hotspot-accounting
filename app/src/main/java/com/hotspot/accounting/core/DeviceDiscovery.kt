package com.hotspot.accounting.core

import android.util.Log
import com.hotspot.accounting.root.RootShell

/**
 * Finds the clients currently associated with our hotspot.
 *
 * There is no single reliable API for this on Android: `WifiManager.connectedClients` was removed,
 * and `TetheringManager` only reports aggregate interface stats. So we read from the kernel and the
 * DHCP server instead, merging three sources:
 *
 *  1. **Neighbour table** (`ip neigh`) — the authoritative "who is actually here right now" list.
 *     Covers IPv4 and IPv6 link-local neighbours, so it sees clients even before they take a DHCP
 *     lease, and it is the only source that reflects a client that just left.
 *  2. **DHCP leases** — gives the *hostname* the client asked for, which makes an unnamed device
 *     recognisable ("Xiaomi-14" rather than a MAC).
 *  3. **`dumpsys` fallback** — some vendor ROMs keep leases only in memory; the tethering service
 *     dump exposes the client list and is the last resort.
 */
object DeviceDiscovery {

    private const val TAG = "DeviceDiscovery"

    data class Client(
        val mac: String,
        val ip: String?,
        val hostname: String?,
        /** Tethering interface this client was seen on, e.g. `ap0` or `rndis0`. */
        val iface: String? = null,
        /** Transport inferred from [iface]. */
        val transport: Transport = Transport.UNKNOWN,
    )

    /** Lease files that different Android generations / vendors use for the tethering DHCP server. */
    private val LEASE_FILES = listOf(
        "/data/misc/dhcp/dnsmasq.leases",
        "/data/misc/dhcp/dnsmasq.lease",
        "/data/misc/dhcp/dhcpd.leases",
        "/data/vendor/dhcp/dnsmasq.leases",
        "/data/misc/dhcp/dnsmasq.leases.ipv6",
        "/cache/dhcp/dnsmasq.leases",
    )

    /**
     * How a client is attached to this phone.
     *
     * Recorded per client so the UI can say *how* a machine is connected. It matters in practice:
     * a desktop on USB and a phone on Wi-Fi are billed the same but are tuned differently, and the
     * rate limiter has to act on the interface the client is actually on.
     */
    enum class Transport(val label: String) {
        WIFI("WiFi 热点"),
        USB("USB 共享"),
        BLUETOOTH("蓝牙共享"),
        ETHERNET("以太网共享"),
        UNKNOWN("未知"),
    }

    /**
     * A tethering interface and the transport it carries.
     *
     * Ordered by prefix specificity: the first pattern that matches wins, which is why the lists are
     * checked in order rather than as a single set.
     */
    data class TetherInterface(val name: String, val transport: Transport)

    /**
     * Interface name prefixes per transport.
     *
     * Note on `eth`: only `eth` + a digit is accepted (see [classify]). A bare `eth` prefix would also
     * match an interface literally named `eth` or vendor names like `ethm0`, and more importantly the
     * uplink on some ROMs is `eth0` — counting that as a *client* interface would attribute the
     * phone's own upstream traffic to a client.
     */
    private val IFACE_PREFIXES: List<Pair<String, Transport>> = listOf(
        "ap" to Transport.WIFI,
        "swlan" to Transport.WIFI,
        "softap" to Transport.WIFI,
        "wlan1" to Transport.WIFI,
        "wlan2" to Transport.WIFI,
        "rndis" to Transport.USB,
        "usb" to Transport.USB,
        "ncm" to Transport.USB,
        "bt-pan" to Transport.BLUETOOTH,
        "bnep" to Transport.BLUETOOTH,
        "eth" to Transport.ETHERNET,
    )

    /** Classifies an interface name, or null when it is not a tethering interface at all. */
    fun classify(name: String): Transport? {
        for ((prefix, transport) in IFACE_PREFIXES) {
            if (!name.startsWith(prefix)) continue
            // `eth` requires a digit suffix so `eth0`-style uplinks are handled deliberately below,
            // while junk names are rejected.
            if (prefix == "eth") {
                if (name.length > 3 && name[3].isDigit()) return Transport.ETHERNET
                continue
            }
            return transport
        }
        return null
    }

    suspend fun discover(): List<Client> {
        // Interface *names* are what the neighbour filter needs; the transport is carried through
        // per client so the UI can show how each machine is attached.
        val interfaces = tetherInterfaces().map { it.name }
        val neighbours = readNeighbours(interfaces)
        val leases = readLeases()
        val fromDumpsys = if (neighbours.isEmpty() && leases.isEmpty()) readDumpsys() else emptyMap()

        // Merge: MAC is the identity; IP, hostname and interface are filled from whichever source
        // has them. The interface matters for USB tethering, where the client is reachable only on
        // rndis0/usb0 and rate limiting must target that link rather than the Wi-Fi AP.
        val byMac = LinkedHashMap<String, Client>()

        fun merge(mac: String, ip: String?, hostname: String?, iface: String? = null) {
            val m = mac.trim().lowercase()
            if (!MAC_PATTERN.matches(m)) return
            val existing = byMac[m]
            val resolvedIface = iface ?: existing?.iface
            byMac[m] = Client(
                mac = m,
                ip = ip ?: existing?.ip,
                hostname = hostname ?: existing?.hostname,
                iface = resolvedIface,
                transport = resolvedIface?.let { classify(it) } ?: existing?.transport ?: Transport.UNKNOWN,
            )
        }

        for (n in neighbours) merge(n.mac, n.ip, null, n.iface)
        for (l in leases.values) merge(l.mac, l.ip, l.hostname, null)
        for ((mac, c) in fromDumpsys) merge(mac, c.ip, c.hostname, c.iface)

        return byMac.values.toList()
    }

    // ---------------------------------------------------------------------------------------
    // neighbour table
    // ---------------------------------------------------------------------------------------

    private data class Neighbour(val mac: String, val ip: String, val iface: String?)

    private suspend fun readNeighbours(interfaces: List<String>): List<Neighbour> {
        // `-4` and `-6` separately: a single `ip neigh` can be truncated on busy devices, and we
        // want IPv6 link-local neighbours because they reveal clients that have no DHCPv4 lease.
        val ipv4 = RootShell.execOrNull("ip -4 neigh show 2>/dev/null").orEmpty()
        val ipv6 = RootShell.execOrNull("ip -6 neigh show 2>/dev/null").orEmpty()
        val out = mutableListOf<Neighbour>()

        for (line in (ipv4 + "\n" + ipv6).lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 3) continue
            val ip = parts[0]
            val devIndex = parts.indexOfFirst { it == "dev" }
            val dev = if (devIndex >= 0) parts.getOrNull(devIndex + 1) else null
            val lladdrIndex = parts.indexOfFirst { it == "lladdr" }
            val mac = if (lladdrIndex >= 0) parts.getOrNull(lladdrIndex + 1) else null

            if (mac == null || !MAC_PATTERN.matches(mac.lowercase())) continue
            // Restrict to tether interfaces when we could identify them; otherwise accept all,
            // because a wrong interface filter silently produces zero clients.
            if (interfaces.isNotEmpty() && dev != null && dev !in interfaces) continue

            // REACHABLE/STALE/DELAY are all "present". FAILED/INCOMPLETE means it went away.
            val state = parts.lastOrNull()?.uppercase()
            if (state == "FAILED" || state == "INCOMPLETE") continue

            out += Neighbour(mac.lowercase(), ip, dev)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // DHCP leases
    // ---------------------------------------------------------------------------------------

    /**
     * One parsed DHCP lease.
     *
     * `internal` rather than `private` because [parseLeaseLine] is internal (it is unit-testable in
     * isolation) and Kotlin forbids an internal function from exposing a more private type.
     */
    internal data class Lease(val mac: String, val ip: String, val hostname: String?)

    private suspend fun readLeases(): Map<String, Lease> {
        val result = LinkedHashMap<String, Lease>()
        for (path in LEASE_FILES) {
            val content = RootShell.execOrNull("cat '$path' 2>/dev/null") ?: continue
            if (content.isBlank()) continue
            for (line in content.lineSequence()) {
                val lease = parseLeaseLine(line) ?: continue
                // Later files win only if they add a hostname we did not have.
                val existing = result[lease.mac]
                result[lease.mac] = if (existing != null && existing.hostname != null) existing else lease
            }
            if (result.isNotEmpty()) {
                Log.d(TAG, "read ${result.size} leases from $path")
            }
        }
        return result
    }

    /**
     * Parses dnsmasq's lease format: `<expiry> <mac> <ip> <hostname> <client-id>`
     * and dhcpd's `lease <ip> { hardware ethernet <mac>; client-hostname "<name>"; }` form.
     */
    internal fun parseLeaseLine(line: String): Lease? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null

        // dnsmasq style
        val parts = t.split(Regex("\\s+"))
        if (parts.size >= 3 && MAC_PATTERN.matches(parts[1].lowercase()) && looksLikeIpv4(parts[2])) {
            val host = parts.getOrNull(3)?.takeIf { it != "*" && it.isNotBlank() }
            return Lease(parts[1].lowercase(), parts[2], host)
        }

        // dhcpd style
        val macMatch = Regex("""hardware\s+ethernet\s+([0-9a-fA-F:]{17})""").find(t)
        val ipMatch = Regex("""lease\s+(\d+\.\d+\.\d+\.\d+)""").find(t)
        if (macMatch != null && ipMatch != null) {
            val host = Regex("""client-hostname\s+"([^"]*)"""").find(t)?.groupValues?.get(1)
            return Lease(macMatch.groupValues[1].lowercase(), ipMatch.groupValues[1], host)
        }
        return null
    }

    private fun looksLikeIpv4(s: String): Boolean =
        s.count { it == '.' } == 3 && s.all { it.isDigit() || it == '.' }

    // ---------------------------------------------------------------------------------------
    // dumpsys fallback
    // ---------------------------------------------------------------------------------------

    /**
     * Last-resort source. The tethering dump lists associated clients on most ROMs, and unlike the
     * neighbour table it survives a client that is associated but has not transmitted yet.
     */
    private suspend fun readDumpsys(): Map<String, Client> {
        val out = RootShell.execOrNull("dumpsys tethering 2>/dev/null") ?: return emptyMap()
        val result = LinkedHashMap<String, Client>()
        var currentMac: String? = null
        var currentIp: String? = null
        for (line in out.lineSequence()) {
            val macMatch = Regex("""([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""").find(line)
            if (macMatch != null) {
                val m = macMatch.groupValues[1].lowercase()
                if (MAC_PATTERN.matches(m)) {
                    currentMac = m
                    currentIp = Regex("""(\d+\.\d+\.\d+\.\d+)""").find(line)?.groupValues?.get(1)
                    result[m] = Client(m, currentIp, null, iface = null, transport = Transport.UNKNOWN)
                }
            } else if (currentMac != null) {
                val ip = Regex("""(\d+\.\d+\.\d+\.\d+)""").find(line)?.groupValues?.get(1)
                if (ip != null && currentIp == null) {
                    currentIp = ip
                    val prev = result[currentMac!!]
                    if (prev != null) result[currentMac!!] = prev.copy(ip = ip)
                }
                val host = Regex("""(?:hostname|deviceName|name)\s*[:=]\s*"?([^\s",]+)""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)
                if (host != null) {
                    val prev = result[currentMac!!]
                    if (prev != null) result[currentMac!!] = prev.copy(hostname = host)
                }
            }
        }
        return result
    }

    // ---------------------------------------------------------------------------------------
    // interface detection
    // ---------------------------------------------------------------------------------------

    /**
     * Tethering interfaces currently present, each labelled with the transport it carries.
     *
     * A phone can offer several at once — Wi-Fi hotspot plus USB tethering is a common combination —
     * so this returns all of them rather than picking one. The rate limiter needs the specific
     * interface a client sits on, and the UI shows the transport so the user can tell a laptop on USB
     * from a phone on Wi-Fi.
     */
    suspend fun tetherInterfaces(): List<TetherInterface> {
        val out = RootShell.execOrNull("ip -o link show 2>/dev/null").orEmpty()
        val names = out.lineSequence().mapNotNull { line ->
            // Format: `3: wlan0: <BROADCAST,MULTICAST,UP> mtu 1500 ...`
            val afterIndex = line.substringAfter(':', "")
            if (afterIndex.isEmpty()) return@mapNotNull null
            afterIndex.trim().substringBefore('@').substringBefore(':').trim().ifEmpty { null }
        }.toList()

        val classified = names.mapNotNull { n -> classify(n)?.let { TetherInterface(n, it) } }

        // `wlan0` is ambiguous: it is the uplink when the hotspot is up on another interface, but it is
        // the AP interface on single-radio devices. Only used when nothing else matched.
        if (classified.isEmpty() && names.contains("wlan0")) {
            return listOf(TetherInterface("wlan0", Transport.WIFI))
        }
        return classified.distinctBy { it.name }
    }

    /** Just the interface names, for callers that do not care about the transport. */
    suspend fun tetherInterfaceNames(): List<String> = tetherInterfaces().map { it.name }

    /**
     * The interface a given client address is reachable on.
     *
     * `ip neigh` reports the device each neighbour was learned on, which is what lets rate limiting
     * act on the right link when both Wi-Fi and USB tethering are active. Returns null when the
     * neighbour is unknown or was seen on an interface we do not consider a tether.
     */
    suspend fun interfaceForAddress(ip: String): String? {
        if (ip.isBlank()) return null
        val out = RootShell.execOrNull("ip neigh show '$ip' 2>/dev/null").orEmpty()
        for (line in out.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            val devIndex = parts.indexOfFirst { it == "dev" }
            val dev = if (devIndex >= 0) parts.getOrNull(devIndex + 1) else null
            if (dev != null && classify(dev) != null) return dev
            if (dev != null) return dev
        }
        return null
    }

    /**
     * True for locally administered (randomised) MAC addresses, i.e. bit 1 of the first octet set.
     * Modern phones rotate these, so a returning device may look new. Surfaced in the UI so the
     * user understands why a nickname did not stick.
     */
    fun isLocallyAdministered(mac: String): Boolean {
        val first = mac.substringBefore(':').toIntOrNull(16) ?: return false
        return (first and 0x02) != 0
    }
}

/**
 * Best-effort MAC OUI -> vendor mapping.
 *
 * Only a small table is embedded: the point is to help the user recognise a device, and a full
 * IEEE registry would bloat the APK for marginal benefit. Unknown prefixes fall back to null.
 */
object MacVendor {

    private val TABLE: Map<String, String> = mapOf(
        // Xiaomi / Redmi / POCO
        "00:9E:C8" to "小米", "04:CF:8C" to "小米", "0C:1D:AF" to "小米", "10:2A:B3" to "小米",
        "14:F6:5A" to "小米", "18:59:36" to "小米", "20:47:DA" to "小米", "28:6C:07" to "小米",
        "34:CE:00" to "小米", "38:A4:ED" to "小米", "3C:BD:3E" to "小米", "44:23:7C" to "小米",
        "50:8F:4C" to "小米", "58:44:98" to "小米", "64:09:80" to "小米", "64:B4:73" to "小米",
        "68:AB:BC" to "小米", "6C:F7:84" to "小米", "74:51:BA" to "小米", "78:02:F8" to "小米",
        "7C:1D:D9" to "小米", "8C:BE:BE" to "小米", "98:FA:E3" to "小米", "9C:99:A0" to "小米",
        "A4:50:46" to "小米", "AC:C1:EE" to "小米", "B0:E2:35" to "小米", "C4:0B:CB" to "小米",
        "D4:97:0B" to "小米", "E4:AA:EC" to "小米", "F0:B4:29" to "小米", "F4:8B:32" to "小米",
        "FC:64:BA" to "小米",
        // Apple
        "00:1B:63" to "苹果", "04:0C:CE" to "苹果", "04:54:53" to "苹果", "0C:30:21" to "苹果",
        "10:40:F3" to "苹果", "14:10:9F" to "苹果", "18:AF:61" to "苹果", "1C:AB:A7" to "苹果",
        "28:CF:E9" to "苹果", "2C:F0:EE" to "苹果", "34:C0:59" to "苹果", "3C:07:54" to "苹果",
        "40:6C:8F" to "苹果", "44:00:10" to "苹果", "48:60:BC" to "苹果", "4C:57:CA" to "苹果",
        "50:ED:3C" to "苹果", "58:55:CA" to "苹果", "5C:95:AE" to "苹果", "60:33:4B" to "苹果",
        "64:76:BA" to "苹果", "68:AB:1E" to "苹果", "6C:40:08" to "苹果", "70:DE:E2" to "苹果",
        "74:E2:F5" to "苹果", "78:31:C1" to "苹果", "7C:D1:C3" to "苹果", "80:BE:05" to "苹果",
        "84:38:35" to "苹果", "88:66:A5" to "苹果", "8C:85:90" to "苹果", "90:B0:ED" to "苹果",
        "94:E9:6A" to "苹果", "98:01:A7" to "苹果", "9C:F3:87" to "苹果", "A0:99:9B" to "苹果",
        "A4:83:E7" to "苹果", "A8:66:7F" to "苹果", "AC:BC:32" to "苹果", "B0:34:95" to "苹果",
        "B8:17:C2" to "苹果", "BC:52:B7" to "苹果", "C0:9F:42" to "苹果", "C8:2A:14" to "苹果",
        "CC:29:F5" to "苹果", "D0:23:DB" to "苹果", "D4:61:9D" to "苹果", "D8:00:4D" to "苹果",
        "DC:2B:2A" to "苹果", "E0:B9:BA" to "苹果", "E4:8B:7F" to "苹果", "E8:8D:28" to "苹果",
        "F0:18:98" to "苹果", "F4:5C:89" to "苹果", "F8:1E:DF" to "苹果", "FC:D8:48" to "苹果",
        // Huawei / Honor
        "00:E0:FC" to "华为", "04:BD:70" to "华为", "08:19:A6" to "华为", "0C:37:DC" to "华为",
        "10:47:80" to "华为", "14:30:04" to "华为", "18:C5:8A" to "华为", "20:0B:C7" to "华为",
        "24:69:A5" to "华为", "28:3C:E4" to "华为", "2C:AB:00" to "华为", "30:87:30" to "华为",
        "34:6B:D3" to "华为", "38:F8:89" to "华为", "3C:CD:57" to "华为", "40:4D:8E" to "华为",
        "48:00:31" to "华为", "4C:1F:CC" to "华为", "50:01:D9" to "华为", "54:89:98" to "华为",
        "5C:7D:5E" to "华为", "60:DE:44" to "华为", "64:A6:51" to "华为", "68:A0:F6" to "华为",
        "6C:92:CF" to "华为", "70:72:3C" to "华为", "74:88:8A" to "华为", "78:D7:52" to "华为",
        "7C:11:CB" to "华为", "80:B6:86" to "华为", "84:A8:E4" to "华为", "88:53:D4" to "华为",
        "8C:34:FD" to "华为", "90:67:1C" to "华为", "94:04:9C" to "华为", "98:E7:F5" to "华为",
        "9C:28:EF" to "华为", "A0:8C:F8" to "华为", "A4:C6:4F" to "华为", "A8:C8:3A" to "华为",
        "AC:E2:15" to "华为", "B0:5B:67" to "华为", "B4:15:13" to "华为", "B8:08:D7" to "华为",
        "BC:E0:9C" to "华为", "C0:70:09" to "华为", "C4:07:2F" to "华为", "C8:94:BB" to "华为",
        "CC:A2:23" to "华为", "D0:7A:B5" to "华为", "D4:6A:6A" to "华为", "D8:49:2F" to "华为",
        "DC:D2:FC" to "华为", "E0:24:7F" to "华为", "E4:A7:C5" to "华为", "E8:08:8B" to "华为",
        "EC:23:3D" to "华为", "F0:43:47" to "华为", "F4:55:9C" to "华为", "F8:01:13" to "华为",
        "FC:48:EF" to "华为",
        // OPPO / OnePlus / realme
        "00:1E:75" to "OPPO", "04:5F:A7" to "OPPO", "08:9E:01" to "OPPO", "0C:1C:57" to "OPPO",
        "10:2E:AF" to "OPPO", "14:9F:3C" to "OPPO", "18:9E:FC" to "OPPO", "1C:77:F6" to "OPPO",
        "20:A9:0E" to "OPPO", "24:DA:9B" to "OPPO", "28:D4:1E" to "OPPO", "2C:5B:B8" to "OPPO",
        "30:5A:3A" to "OPPO", "34:80:B3" to "OPPO", "38:BC:01" to "OPPO", "3C:F7:2A" to "OPPO",
        "40:45:DA" to "OPPO", "44:6E:E5" to "OPPO", "48:5A:B6" to "OPPO", "4C:1A:3D" to "OPPO",
        "54:35:30" to "OPPO", "58:9E:C6" to "OPPO", "5C:71:0D" to "OPPO",
        "60:12:8B" to "OPPO", "64:06:5F" to "OPPO", "68:DB:CA" to "OPPO", "6C:5A:B0" to "OPPO",
        "70:8A:09" to "OPPO", "74:23:44" to "OPPO", "78:4B:87" to "OPPO", "7C:B0:C2" to "OPPO",
        "80:EA:CA" to "OPPO", "84:9A:40" to "OPPO", "88:9F:6F" to "OPPO", "8C:89:A5" to "OPPO",
        "90:5C:44" to "OPPO", "94:65:2D" to "OPPO", "98:CB:27" to "OPPO", "9C:B6:D0" to "OPPO",
        "A0:5F:B9" to "OPPO", "A4:60:11" to "OPPO", "A8:7B:39" to "OPPO", "AC:1F:74" to "OPPO",
        "B0:44:14" to "OPPO", "B4:CD:27" to "OPPO", "B8:5E:7B" to "OPPO", "BC:87:FA" to "OPPO",
        "C0:EE:FB" to "OPPO", "C4:6E:1F" to "OPPO", "C8:0C:C8" to "OPPO", "CC:2D:1B" to "OPPO",
        "D0:39:72" to "OPPO", "D4:5D:64" to "OPPO", "D8:C4:6A" to "OPPO", "DC:37:57" to "OPPO",
        "E0:BB:9E" to "OPPO", "E4:5A:A2" to "OPPO", "E8:BB:3E" to "OPPO", "EC:5C:68" to "OPPO",
        "F4:6A:92" to "OPPO", "F8:7B:20" to "OPPO", "FC:19:28" to "OPPO",
        // vivo / iQOO
        "00:8E:F2" to "vivo", "04:F7:E4" to "vivo", "08:4F:0A" to "vivo", "0C:8B:FD" to "vivo",
        "10:20:30" to "vivo", "14:9D:09" to "vivo", "18:5E:0F" to "vivo", "1C:1D:86" to "vivo",
        "20:32:33" to "vivo", "24:8A:07" to "vivo", "28:B2:BD" to "vivo", "2C:BE:08" to "vivo",
        "30:74:96" to "vivo", "34:2D:0D" to "vivo", "38:19:2F" to "vivo", "3C:91:80" to "vivo",
        "40:5D:82" to "vivo", "44:65:0D" to "vivo", "48:85:2A" to "vivo", "4C:0F:6E" to "vivo",
        "50:55:27" to "vivo", "54:D2:72" to "vivo", "58:2D:34" to "vivo", "5C:5A:EA" to "vivo",
        "60:8F:5C" to "vivo", "64:CC:2E" to "vivo", "68:D4:8C" to "vivo", "6C:24:08" to "vivo",
        "70:2C:1F" to "vivo", "74:5C:4B" to "vivo", "78:1F:DB" to "vivo", "7C:11:BE" to "vivo",
        "80:6C:1B" to "vivo", "84:8E:0C" to "vivo", "88:28:B3" to "vivo", "8C:BE:BE" to "vivo",
        "90:5F:2B" to "vivo", "94:87:E0" to "vivo", "98:6D:35" to "vivo", "9C:5C:F9" to "vivo",
        "A0:1B:29" to "vivo", "A4:91:B1" to "vivo", "A8:6B:AD" to "vivo", "AC:3A:7A" to "vivo",
        "B0:4E:26" to "vivo", "B4:0B:44" to "vivo", "B8:89:CA" to "vivo", "BC:32:5F" to "vivo",
        "C0:48:E6" to "vivo", "C4:6A:B7" to "vivo", "C8:0C:53" to "vivo", "CC:5E:F4" to "vivo",
        "D0:03:DF" to "vivo", "D4:6E:5C" to "vivo", "D8:10:9F" to "vivo", "DC:2C:6E" to "vivo",
        "E0:6D:17" to "vivo", "E4:0D:36" to "vivo", "E8:54:84" to "vivo", "EC:DF:3A" to "vivo",
        "F0:1C:2D" to "vivo", "F4:5E:AB" to "vivo", "F8:8F:CA" to "vivo", "FC:0F:E6" to "vivo",
        // Samsung
        "00:07:AB" to "三星", "04:18:D6" to "三星", "08:37:3D" to "三星", "0C:71:5D" to "三星",
        "10:D5:42" to "三星", "14:49:E0" to "三星", "18:3A:2D" to "三星", "1C:5A:3E" to "三星",
        "20:13:E0" to "三星", "24:4B:03" to "三星", "28:39:5E" to "三星", "2C:AE:2B" to "三星",
        "30:19:66" to "三星", "34:23:BA" to "三星", "38:AA:3C" to "三星", "3C:5A:37" to "三星",
        "40:0E:85" to "三星", "44:4E:1A" to "三星", "48:5A:3F" to "三星", "4C:3C:16" to "三星",
        "50:32:75" to "三星", "54:88:0E" to "三星", "58:23:8C" to "三星", "5C:0A:5B" to "三星",
        "60:6B:BD" to "三星", "64:B8:53" to "三星", "68:EB:AE" to "三星", "6C:2F:2C" to "三星",
        "70:F9:27" to "三星", "74:45:8A" to "三星", "78:1F:0A" to "三星", "7C:61:66" to "三星",
        "80:57:19" to "三星", "84:25:DB" to "三星", "88:36:5F" to "三星", "8C:77:12" to "三星",
        "90:F1:AA" to "三星", "94:35:0A" to "三星", "98:0C:82" to "三星", "9C:02:98" to "三星",
        "A0:21:95" to "三星", "A4:EB:D3" to "三星", "A8:06:00" to "三星", "AC:5F:3E" to "三星",
        "B0:DF:3A" to "三星", "B4:3A:28" to "三星", "B8:5A:73" to "三星", "BC:14:85" to "三星",
        "C0:BD:D1" to "三星", "C4:42:02" to "三星", "C8:19:F7" to "三星", "CC:07:AB" to "三星",
        "D0:22:BE" to "三星", "D4:87:D8" to "三星", "D8:57:EF" to "三星", "DC:71:44" to "三星",
        "E0:99:71" to "三星", "E4:40:E2" to "三星", "E8:50:8B" to "三星", "EC:1F:72" to "三星",
        "F0:25:B7" to "三星", "F4:9F:54" to "三星", "F8:D0:BD" to "三星", "FC:00:12" to "三星",
    )

    fun lookup(mac: String): String? {
        val prefix = mac.lowercase().take(8)
        return TABLE[prefix.uppercase()]
    }
}
