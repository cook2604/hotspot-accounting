package com.hotspot.accounting.data

import android.content.Context
import android.util.Log
import com.hotspot.accounting.core.CounterBackend
import com.hotspot.accounting.core.CounterManager
import com.hotspot.accounting.core.DeviceDiscovery
import com.hotspot.accounting.core.IptablesBackend
import com.hotspot.accounting.core.LiveDevice
import com.hotspot.accounting.core.NetControl
import com.hotspot.accounting.core.NftBridgeBackend
import com.hotspot.accounting.core.VerifyResult
import com.hotspot.accounting.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Overall engine state, surfaced to the UI so failures are visible rather than silent. */
sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data class Running(val backend: String) : EngineState
    data class Failed(val message: String) : EngineState
}

/** A named reporting range for the history screen. */
enum class ReportRange(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    THIS_WEEK("本周"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    ALL("全部"),
}

/**
 * Single source of truth for the UI.
 *
 * Chooses the best available kernel backend at startup, runs the collection loop, and exposes both
 * live counters and historical aggregates. All root and database work happens off the main thread.
 */
class HotspotRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val db = AppDatabase.get(context)
    val settings = AppSettings.get(context)
    val netControl = NetControl()

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _liveDevices = MutableStateFlow<List<LiveDevice>>(emptyList())
    val liveDevices: StateFlow<List<LiveDevice>> = _liveDevices.asStateFlow()

    private val _lastVerify = MutableStateFlow<VerifyResult?>(null)
    val lastVerify: StateFlow<VerifyResult?> = _lastVerify.asStateFlow()

    // Reading the counter table while a poll is mid-flight can observe a reset, so serialise them.
    private val pollLock = Mutex()

    private var backend: CounterBackend? = null
    private var manager: CounterManager? = null

    val devicesFlow = db.devices().observeAll()

    /**
     * Starts the engine, preferring the nftables bridge backend and falling back to iptables.
     *
     * Every fallback is logged and reported through [engineState] so a user whose kernel lacks
     * bridge netfilter understands *why* numbers might differ from expectations.
     */
    suspend fun start() {
        _engineState.value = EngineState.Starting
        if (!RootShell.isAvailable()) {
            _engineState.value = EngineState.Failed("未获得 root 权限，无法读取流量计数器")
            return
        }

        val chosen = pickBackend()
        if (chosen == null) {
            _engineState.value = EngineState.Failed(
                "当前内核既不支持 nftables bridge，也不支持 iptables MAC 计数"
            )
            return
        }

        backend = chosen
        val mgr = CounterManager(
            db = db,
            backend = chosen,
            // New devices inherit the app-wide rate so the user need not price each one by hand.
            defaultBilling = {
                settings.defaultPricePerGb.value to settings.defaultBillable.value
            },
        )
        manager = mgr

        if (!mgr.start()) {
            _engineState.value = EngineState.Failed(mgr.lastError() ?: "计数规则安装失败")
            return
        }

        _engineState.value = EngineState.Running(chosen.name)
        Log.i(TAG, "engine running with backend: ${chosen.name}")

        mgr.launchFlushing(scope)

        scope.launch {
            while (true) {
                pollLock.withLock {
                    val devices = runCatching { mgr.pollAndCollect() }
                        .onFailure { Log.e(TAG, "poll failed: ${it.message}") }
                        .getOrDefault(emptyList())
                    _liveDevices.value = devices
                }
                kotlinx.coroutines.delay(CounterManager.DEFAULT_INTERVAL_MS)
            }
        }
    }

    private suspend fun pickBackend(): CounterBackend? {
        val nft = NftBridgeBackend()
        if (nft.isSupported()) return nft
        Log.w(TAG, "nftables bridge backend unavailable, trying iptables")
        val ipt = IptablesBackend()
        if (ipt.isSupported()) return ipt
        return null
    }

    /** Stops collection and removes our kernel rules, flushing accumulated usage first. */
    suspend fun stop() {
        manager?.stop()
        manager = null
        backend = null
        _liveDevices.value = emptyList()
        _engineState.value = EngineState.Idle
    }

    /** Runs the counter self-test, which is how a user proves rules are actually counting. */
    suspend fun runSelfTest(seconds: Int = 5): VerifyResult? {
        val mgr = manager ?: return null
        val result = runCatching { mgr.verifyCounting(seconds) }
            .onFailure { Log.e(TAG, "self-test failed: ${it.message}") }
            .getOrNull()
        _lastVerify.value = result
        return result
    }

    // ---------------------------------------------------------------------------------------
    // Device mutations
    // ---------------------------------------------------------------------------------------

    suspend fun rename(mac: String, nickname: String?) {
        val device = db.devices().get(mac) ?: return
        db.devices().update(device.copy(nickname = nickname?.takeIf { it.isNotBlank() }))
    }

    suspend fun updateBilling(
        mac: String,
        nickname: String?,
        pricePerGb: Double,
        note: String?,
        billable: Boolean,
        limitDownKbps: Int?,
        limitUpKbps: Int?,
    ) {
        db.devices().updateBillingProfile(
            mac = mac,
            nickname = nickname?.takeIf { it.isNotBlank() },
            pricePerGb = pricePerGb,
            note = note?.takeIf { it.isNotBlank() },
            billable = billable,
            limitDownKbps = limitDownKbps,
            limitUpKbps = limitUpKbps,
        )
    }

    suspend fun setBlocked(mac: String, blocked: Boolean): Result<Unit> {
        val device = db.devices().get(mac)
        val result = netControl.setBlocked(mac, blocked, device?.lastIp)
        if (result.isSuccess) {
            db.devices().setBlocked(mac, blocked)
        }
        return result
    }

    suspend fun applyRateLimit(
        mac: String,
        downKbps: Int?,
        upKbps: Int?,
        ifaceHint: String? = null,
    ): Result<Unit> {
        val device = db.devices().get(mac)
            ?: return Result.failure(IllegalStateException("设备不存在"))
        val result = netControl.setRateLimit(mac, device.lastIp, downKbps, upKbps, ifaceHint)
        if (result.isSuccess) {
            db.devices().updateBillingProfile(
                mac = mac,
                nickname = device.nickname,
                pricePerGb = device.pricePerGb,
                note = device.billingNote,
                billable = device.billable,
                limitDownKbps = downKbps,
                limitUpKbps = upKbps,
            )
        }
        return result
    }

    suspend fun revertAllControls(): List<String> = netControl.revertAll()

    suspend fun deleteDevice(mac: String) {
        backend?.removeDevice(mac)
        db.usage().deleteForDevice(mac)
        db.devices().delete(mac)
    }

    suspend fun forgetOldUsage(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        return db.usage().purgeBefore(cutoff)
    }

    /** Persists the app-wide default billing rate. */
    fun setDefaultPricePerGb(value: Double) = settings.setDefaultPricePerGb(value)

    fun setDefaultBillable(value: Boolean) = settings.setDefaultBillable(value)

    /**
     * Applies the default rate to every device that currently has no price.
     *
     * Returns how many devices were updated so the caller can report a concrete number rather than
     * a vague success message.
     */
    suspend fun applyDefaultPriceToUnpriced(): Int {
        val price = settings.defaultPricePerGb.value
        if (price <= 0.0) return 0
        val changed = db.devices().applyDefaultPriceToUnpriced(price, settings.defaultBillable.value)
        Log.i(TAG, "applied default price $price/GB to $changed unpriced device(s)")
        return changed
    }

    // ---------------------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------------------

    suspend fun report(range: ReportRange): ReportSnapshot {
        val (from, to) = rangeBounds(range)
        val rows = db.usage().usageRows(from, to)
        return ReportSnapshot(
            range = range,
            from = from,
            to = to,
            rows = rows,
            totalBytes = rows.sumOf { it.total },
            totalCharge = rows.sumOf { it.charge },
        )
    }

    suspend fun dailyTotals(days: Int = 30): List<DailyTotal> {
        val now = System.currentTimeMillis()
        val from = now - days * 86_400_000L
        return db.usage().dailyTotals(from, now + 1)
    }

    /** Serialises a report to CSV text, for sharing or spreadsheet import. */
    fun toCsv(snapshot: ReportSnapshot): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("设备名称,MAC,主机名,上传(字节),下载(字节),合计(字节),合计(GB),单价(元/GB),应收(元),备注\n")
        for (row in snapshot.rows) {
            val gb = row.total / (1024.0 * 1024.0 * 1024.0)
            sb.append(escapeCsv(row.displayName)).append(',')
            sb.append(row.mac).append(',')
            sb.append(escapeCsv(row.hostname ?: "")).append(',')
            sb.append(row.txBytes).append(',')
            sb.append(row.rxBytes).append(',')
            sb.append(row.total).append(',')
            sb.append(String.format(Locale.US, "%.4f", gb)).append(',')
            sb.append(String.format(Locale.US, "%.4f", row.pricePerGb)).append(',')
            sb.append(String.format(Locale.US, "%.2f", row.charge)).append(',')
            sb.append(escapeCsv(""))
            sb.append('\n')
        }
        sb.append('\n')
        sb.append("统计区间,").append(fmt.format(Date(snapshot.from))).append(" 至 ")
            .append(fmt.format(Date(snapshot.to))).append('\n')
        sb.append("总用量(GB),").append(
            String.format(Locale.US, "%.4f", snapshot.totalBytes / (1024.0 * 1024.0 * 1024.0))
        ).append('\n')
        sb.append("应收合计(元),").append(String.format(Locale.US, "%.2f", snapshot.totalCharge)).append('\n')
        return sb.toString()
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    /**
     * Tethering interfaces currently available, each with its transport.
     *
     * Returns the full objects rather than bare names so the UI can label a laptop on USB tethering
     * separately from a phone on the Wi-Fi hotspot.
     */
    suspend fun tetherInterfaces(): List<DeviceDiscovery.TetherInterface> =
        DeviceDiscovery.tetherInterfaces()

    private fun rangeBounds(range: ReportRange): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()
        return when (range) {
            ReportRange.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            ReportRange.YESTERDAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                todayStart - 86_400_000L to todayStart
            }
            ReportRange.THIS_WEEK -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            ReportRange.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            ReportRange.LAST_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val thisMonthStart = cal.timeInMillis
                cal.add(Calendar.MONTH, -1)
                cal.timeInMillis to thisMonthStart
            }
            ReportRange.ALL -> 0L to now
        }
    }

    companion object {
        private const val TAG = "HotspotRepository"

        @Volatile private var instance: HotspotRepository? = null

        fun get(context: Context): HotspotRepository =
            instance ?: synchronized(this) {
                instance ?: HotspotRepository(context.applicationContext).also { instance = it }
            }
    }
}

/** A complete report for one range: per-device rows plus totals. */
data class ReportSnapshot(
    val range: ReportRange,
    val from: Long,
    val to: Long,
    val rows: List<DeviceUsageRow>,
    val totalBytes: Long,
    val totalCharge: Double,
) {
    val totalGb: Double get() = totalBytes / (1024.0 * 1024.0 * 1024.0)
}
