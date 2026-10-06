package com.hotspot.accounting.core

import android.util.Log
import androidx.room.withTransaction
import com.hotspot.accounting.data.AppDatabase
import com.hotspot.accounting.data.Billing
import com.hotspot.accounting.data.DeviceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** A hotspot client as discovered from the kernel, before it is enriched from the database. */
data class DiscoveredClient(
    val mac: String,
    val ip: String?,
    val hostname: String?,
    /** Tethering interface the client was seen on, e.g. `ap0` (Wi-Fi) or `rndis0` (USB). */
    val iface: String? = null,
    /** Transport inferred from [iface]; lets the UI distinguish USB and Wi-Fi clients. */
    val transport: DeviceDiscovery.Transport = DeviceDiscovery.Transport.UNKNOWN,
)

/** A live device row combining persisted metadata with in-flight counters. */
data class LiveDevice(
    val device: DeviceEntity,
    /** Cumulative bytes since the accounting rules were installed. */
    val txBytes: Long,
    val rxBytes: Long,
    /** Bytes accrued during the most recent poll interval, for a live rate readout. */
    val txDelta: Long = 0L,
    val rxDelta: Long = 0L,
    val online: Boolean,
    /**
     * Interface this client is currently attached to, and the transport it implies.
     *
     * Deliberately in-memory only. Persisting it would need a Room column, and this database uses a
     * destructive migration — discarding the entire usage history to store a cosmetic label would be
     * a bad trade. The value is re-derived on every poll, and the rate limiter looks the interface up
     * live rather than trusting a stored one.
     */
    val iface: String? = null,
    val transport: DeviceDiscovery.Transport = DeviceDiscovery.Transport.UNKNOWN,
) {
    val total: Long get() = txBytes + rxBytes
    val delta: Long get() = txDelta + rxDelta
}

/**
 * Owns device discovery and the counter -> database pipeline.
 *
 * Responsibilities:
 *  1. Discover connected clients (neighbour table + DHCP lease files + `dumpsys`).
 *  2. Keep one kernel counter per discovered MAC, pruning counters for departed devices.
 *  3. Diff raw kernel counters into per-bucket deltas and persist them transactionally.
 *  4. Detect counter resets (hotspot restart / reboot) so usage is never double-counted or lost.
 *
 * The delta pipeline works like this. The kernel gives us a *monotonic cumulative* byte count per
 * MAC. We store the last raw reading in [DeviceEntity.rawTx]/[DeviceEntity.rawRx]. Each poll we
 * compute `now - stored`, add that delta to an in-memory pending map, and persist the new raw
 * reading. Pending deltas are flushed into hour buckets and only cleared after the database
 * transaction commits, so a crash either replays a flush (upsert makes it idempotent against the
 * stored raw value) or loses at most one poll interval.
 */
class CounterManager(
    private val db: AppDatabase,
    private val backend: CounterBackend,
    /**
     * Supplies the app-wide default price per GB and billable flag for newly discovered devices.
     *
     * Injected as a callback rather than passing AppSettings in, so this class keeps no dependency on
     * Android's preference storage and stays straightforward to test.
     */
    private val defaultBilling: suspend () -> Pair<Double, Boolean> = { 0.0 to true },
) {
    private val pending = ConcurrentHashMap<String, PendingDelta>()

    /** Last raw reading seen this process lifetime, per MAC. */
    private val lastRaw = ConcurrentHashMap<String, Pair<Long, Long>>()

    private data class PendingDelta(var tx: Long, var rx: Long)

    @Volatile private var installed = false

    @Volatile private var lastError: String? = null

    fun lastError(): String? = lastError

    val backendName: String get() = backend.name

    suspend fun isSupported(): Boolean = backend.isSupported()

    /**
     * Drains traffic through the accounting chain for [seconds] and reports whether counters moved.
     *
     * This is the only way to prove the rules are positioned where real traffic flows: a ruleset can
     * install cleanly and still count zero if a vendor rule accepts packets upstream.
     */
    suspend fun verifyCounting(seconds: Int = 5): VerifyResult =
        backend.verifyCounting(seconds)

    /**
     * Prepares the kernel ruleset and reconciles counters with known devices.
     * Returns false when there is no usable backend.
     */
    suspend fun start(): Boolean {
        if (!backend.isSupported()) {
            lastError = "当前内核不支持所选计数后端"
            Log.e(TAG, lastError!!)
            return false
        }
        if (!backend.ensureInstalled()) {
            lastError = "无法创建流量计数规则（root 权限或被 SELinux 拒绝）"
            Log.e(TAG, lastError!!)
            return false
        }
        installed = true
        lastError = null
        // Re-install counters for devices we already know about, then pick up new arrivals.
        val macs = db.devices().allMacs()
        for (mac in macs) {
            runCatching { backend.addDevice(mac) }
                .onFailure { Log.w(TAG, "re-add counter for $mac failed: ${it.message}") }
        }
        // A fresh ruleset means every stored baseline is meaningless: bump epochs so the first
        // reading after this point is treated as a fresh start rather than a huge negative delta.
        resetBaselines()
        return true
    }

    suspend fun stop() {
        // Flush before tearing down so nothing accumulated in this lifetime is lost.
        runCatching { flush() }
        runCatching { backend.uninstall() }
        installed = false
        lastRaw.clear()
        pending.clear()
        counterMacs = null
    }

    /** Marks every device's baseline as belonging to a new epoch (kernel counters restarted). */
    private suspend fun resetBaselines() {
        val devices = db.devices().getAll()
        db.withTransaction {
            for (d in devices) {
                db.devices().saveCounterBaseline(d.mac, 0L, 0L, d.counterEpoch + 1)
            }
        }
    }

    /**
     * Discovers clients, reconciles kernel counters, and returns the live device list.
     *
     * This is the single entry point the collector service calls on each tick.
     */
    suspend fun pollAndCollect(now: Long = System.currentTimeMillis()): List<LiveDevice> {
        if (!installed) {
            if (!start()) return emptyList()
        }

        val discovered = DeviceDiscovery.discover()
        val known = db.devices().getAll().associateBy { it.mac }

        // Register newly seen clients.
        val newMacs = mutableListOf<String>()
        for (client in discovered) {
            if (known[client.mac] == null) {
                val inserted = db.devices().insertIfAbsent(
                    DeviceEntity(
                        mac = client.mac,
                        hostname = client.hostname,
                        lastIp = client.ip,
                        vendor = MacVendor.lookup(client.mac),
                        firstSeen = now,
                        lastSeen = now,
                    )
                )
                // insertIfAbsent returns -1 when the row already existed.
                if (inserted != -1L) {
                    newMacs += client.mac
                    // Inherit the app-wide billing rate so the user does not have to set the same
                    // price once per device. Skipped when no default is configured, which leaves the
                    // device unbilled until it is priced explicitly.
                    val (defaultPrice, defaultBillable) = defaultBilling()
                    if (defaultPrice > 0.0) {
                        db.devices().applyDefaultPrice(client.mac, defaultPrice, defaultBillable)
                    }
                    Log.i(
                        TAG,
                        "new client ${client.mac} ip=${client.ip} host=${client.hostname} " +
                            "iface=${client.iface ?: "?"} (${client.transport.label})" +
                            if (defaultPrice > 0.0) " (inherited price $defaultPrice/GB)" else "",
                    )
                }
            }
        }

        // Install counters for anything new, and make sure every discovered MAC has one.
        for (client in discovered) {
            if (client.mac in newMacs || !hasCounterFor(client.mac)) {
                runCatching { backend.addDevice(client.mac) }
                    .onSuccess { counterMacs = (counterMacs ?: emptySet()) + client.mac }
                    .onFailure { Log.w(TAG, "addDevice(${client.mac}) failed: ${it.message}") }
            }
        }

        // Refresh last-seen metadata.
        for (client in discovered) {
            db.devices().touch(client.mac, client.ip, client.hostname, now)
        }

        // Read counters and turn them into deltas.
        val counters = runCatching { backend.readCounters() }.getOrElse {
            Log.e(TAG, "readCounters failed: ${it.message}")
            lastError = "读取计数器失败: ${it.message}"
            emptyList()
        }

        val onlineMacs = discovered.map { it.mac }.toSet()
        val deviceMap = db.devices().getAll().associateBy { it.mac }

        // Per-poll deltas, kept separate from the pending flush accumulator so the live view can
        // show a rate without waiting for (or disturbing) the persistence cadence.
        val pollDeltas = HashMap<String, Pair<Long, Long>>()

        for (c in counters) {
            val device = deviceMap[c.mac] ?: continue
            val storedEpoch = device.counterEpoch
            val prev = lastRaw[c.mac]

            // Detect a kernel-side reset: the raw counter went backwards relative to what we hold.
            val resetDetected = prev != null && (c.txBytes < prev.first || c.rxBytes < prev.second)

            val (dTx, dRx) = when {
                resetDetected -> {
                    // Rules were reinstalled; treat the new reading as fresh usage since baseline.
                    Log.w(TAG, "counter reset detected for ${c.mac}, restarting baseline")
                    db.devices().saveCounterBaseline(c.mac, c.txBytes, c.rxBytes, storedEpoch + 1)
                    c.txBytes to c.rxBytes
                }
                prev == null -> {
                    // First observation in this process lifetime. Compare against the persisted
                    // baseline so usage accrued while the service was dead is still captured.
                    val dtx = (c.txBytes - device.rawTx).coerceAtLeast(0L)
                    val drx = (c.rxBytes - device.rawRx).coerceAtLeast(0L)
                    dtx to drx
                }
                else -> (c.txBytes - prev.first).coerceAtLeast(0L) to
                    (c.rxBytes - prev.second).coerceAtLeast(0L)
            }

            if (dTx > 0 || dRx > 0) {
                pending.computeIfAbsent(c.mac) { PendingDelta(0, 0) }.apply {
                    tx += dTx
                    rx += dRx
                }
                pollDeltas[c.mac] = dTx to dRx
            }
            lastRaw[c.mac] = c.txBytes to c.rxBytes
        }

        // Track departures and returns.
        //
        // This is the mechanism that stops an old unpaid balance from silently merging into a new
        // session's usage. Kernel counters reset when the hotspot restarts, and a device that comes
        // back is otherwise indistinguishable from one that never left — the earlier charges and the
        // new ones would simply pile up under the same MAC with no way to tell them apart.
        for ((mac, device) in deviceMap) {
            val present = mac in onlineMacs
            if (!present) {
                // First poll that sees it gone: stamp the departure time.
                if (device.offlineSince == null && now - device.lastSeen > OFFLINE_GRACE_MS) {
                    db.devices().markOffline(mac, now)
                }
            } else {
                // It is back. If it had been away long enough to matter and still owes money, raise
                // the review flag so the user decides whether to carry the debt forward.
                val goneSince = device.offlineSince
                if (goneSince != null) {
                    val awayMs = now - goneSince
                    if (awayMs > RETURN_REVIEW_AFTER_MS && device.needsBillingReview.not()) {
                        val charged = device.chargedTotal
                        val ledger = db.payments().ledgerFor(mac)
                        val outstanding = ledger?.outstanding ?: charged
                        if (outstanding > 0.005) {
                            db.devices().flagBillingReview(mac)
                            Log.i(
                                TAG,
                                "device $mac returned after ${awayMs / 3_600_000}h owing " +
                                    "%.2f; flagged for billing review".format(outstanding),
                            )
                        }
                    }
                    db.devices().clearOffline(mac)
                }
            }
        }

        // Prune counters for devices that are no longer present, so the ruleset stays small.
        // Only prune devices that have been gone for a while to tolerate brief roaming gaps.
        for ((mac, device) in deviceMap) {
            if (mac !in onlineMacs && now - device.lastSeen > PRUNE_AFTER_MS) {
                if (hasCounterFor(mac)) {
                    runCatching { backend.removeDevice(mac) }
                        .onSuccess { counterMacs = (counterMacs ?: emptySet()) - mac }
                    lastRaw.remove(mac)
                }
            }
        }

        flush()

        // Resolve the interface each client is on, so the UI can label USB vs Wi-Fi clients. The
        // interface list is fetched once and the per-client transport comes from the discovery pass,
        // which already knows the device name each neighbour was learned on.
        val ifacesByName = DeviceDiscovery.tetherInterfaces().associateBy { it.name }
        val ifaceByMac = discovered.associate { c ->
            c.mac to c.iface?.let { ifacesByName[it] ?: DeviceDiscovery.TetherInterface(it, c.transport) }
        }

        return buildLiveDevices(deviceMap, counters, onlineMacs, pollDeltas, ifaceByMac)
    }

    /** Persists all pending deltas into hour buckets. Safe to call concurrently with polling. */
    suspend fun flush(bucketStart: Long = hourBucket(System.currentTimeMillis())) {
        if (pending.isEmpty()) return
        // Snapshot and clear first: anything accrued during the transaction becomes the next flush.
        val snapshot = HashMap<String, Pair<Long, Long>>()
        for ((mac, delta) in pending) {
            val tx = delta.tx
            val rx = delta.rx
            if (tx != 0L || rx != 0L) snapshot[mac] = tx to rx
            delta.tx = 0
            delta.rx = 0
        }
        if (snapshot.isEmpty()) return

        try {
            db.withTransaction {
                for ((mac, delta) in snapshot) {
                    db.usage().accumulate(mac, bucketStart, delta.first, delta.second)

                    val device = db.devices().get(mac)
                    if (device != null) {
                        // Charge for the bytes just persisted, at the device's current rate.
                        //
                        // Done incrementally and in the same transaction as the usage, so the money
                        // owed can never drift out of step with the usage recorded. A rate change
                        // applies from this point forward, which is the correct behaviour: past usage
                        // was already charged at the old rate.
                        val bytes = delta.first + delta.second
                        if (device.billable && device.pricePerGb > 0.0 && bytes > 0L) {
                            val amount = Billing.gbFor(bytes) * device.pricePerGb
                            db.devices().addCharge(mac, amount)
                        }

                        // Advance the persisted baseline so a crash right after this loses nothing.
                        val raw = lastRaw[mac]
                        if (raw != null) {
                            db.devices().saveCounterBaseline(
                                mac, raw.first, raw.second, device.counterEpoch
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Put the deltas back so the next flush retries rather than silently dropping usage.
            Log.e(TAG, "flush failed, requeueing: ${t.message}")
            for ((mac, delta) in snapshot) {
                pending.computeIfAbsent(mac) { PendingDelta(0, 0) }.apply {
                    tx += delta.first
                    rx += delta.second
                }
            }
            lastError = "写入数据库失败: ${t.message}"
        }
    }

    /**
     * Cached set of MACs that currently have a kernel counter.
     *
     * Reading the whole counter table is cheap, but the no-arg `nft list` prints the entire ruleset
     * on some builds, so we avoid calling it once per client per poll.
     */
    @Volatile
    private var counterMacs: Set<String>? = null

    private suspend fun hasCounterFor(mac: String): Boolean {
        val cached = counterMacs
        if (cached != null) return mac in cached
        val fresh = backend.readCounters().map { it.mac }.toSet()
        counterMacs = fresh
        return mac in fresh
    }

    private suspend fun buildLiveDevices(
        deviceMap: Map<String, DeviceEntity>,
        counters: List<DeviceTraffic>,
        onlineMacs: Set<String>,
        pollDeltas: Map<String, Pair<Long, Long>>,
        ifaceByMac: Map<String, DeviceDiscovery.TetherInterface?> = emptyMap(),
    ): List<LiveDevice> {
        val byMac = counters.associateBy { it.mac }
        return deviceMap.values.map { d ->
            val c = byMac[d.mac]
            val delta = pollDeltas[d.mac]
            val iface = ifaceByMac[d.mac]
            LiveDevice(
                device = d,
                txBytes = c?.txBytes ?: 0L,
                rxBytes = c?.rxBytes ?: 0L,
                txDelta = delta?.first ?: 0L,
                rxDelta = delta?.second ?: 0L,
                online = d.mac in onlineMacs,
                iface = iface?.name,
                transport = iface?.transport ?: DeviceDiscovery.Transport.UNKNOWN,
            )
        }.sortedWith(compareByDescending<LiveDevice> { it.online }.thenByDescending { it.total })
    }

    /** Runs the collection loop until the scope is cancelled. */
    fun launchPolling(scope: CoroutineScope, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        scope.launch {
            while (isActive) {
                val started = System.currentTimeMillis()
                runCatching { pollAndCollect(started) }
                    .onFailure { Log.e(TAG, "poll failed: ${it.message}") }
                val elapsed = System.currentTimeMillis() - started
                delay((intervalMs - elapsed).coerceAtLeast(500L))
            }
        }
    }

    /** Flushes periodically so a crash loses at most one flush interval of data. */
    fun launchFlushing(scope: CoroutineScope, intervalMs: Long = FLUSH_INTERVAL_MS) {
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { flush() }
            }
        }
    }

    companion object {
        private const val TAG = "CounterManager"

        /** Poll interval. Counters are cheap to read, but each poll costs one root round trip. */
        const val DEFAULT_INTERVAL_MS = 3_000L

        /** Persist cadence. Longer than the poll interval to keep write amplification low. */
        const val FLUSH_INTERVAL_MS = 30_000L

        /** How long a device may be absent from the neighbour table before we drop its counter. */
        private const val PRUNE_AFTER_MS = 10 * 60 * 1000L

        /**
         * Grace period before a device is considered to have left.
         *
         * Clients drop off the neighbour table for a few minutes routinely — Wi-Fi power saving, a
         * brief roam, the phone sleeping. Marking a departure on the first missed poll would make
         * almost every return look like a new session.
         */
        private const val OFFLINE_GRACE_MS = 20 * 60 * 1000L

        /**
         * How long a device must be away before its return is treated as a new session.
         *
         * The whole point is to catch "used it once, left it for weeks, now wants it again". A
         * shorter window would flag ordinary daily leaving-and-returning, which would be noise.
         */
        private const val RETURN_REVIEW_AFTER_MS = 24 * 60 * 60 * 1000L

        /** Rounds a timestamp down to the start of its hour bucket. */
        fun hourBucket(ts: Long): Long = ts - (ts % 3_600_000L)
    }
}
