package com.hotspot.accounting.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hotspot.accounting.core.DeviceDiscovery
import com.hotspot.accounting.core.LiveDevice
import com.hotspot.accounting.core.NetControl
import com.hotspot.accounting.core.VerifyResult
import com.hotspot.accounting.data.DeviceEntity
import com.hotspot.accounting.data.DailyTotal
import com.hotspot.accounting.data.EngineState
import com.hotspot.accounting.data.HotspotRepository
import com.hotspot.accounting.data.ReportRange
import com.hotspot.accounting.data.ReportSnapshot
import com.hotspot.accounting.root.RootShell
import com.hotspot.accounting.service.CollectorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Transient messages shown as snackbars: permission results, self-test output, errors. */
data class UiMessage(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HotspotRepository.get(app)

    val engineState: StateFlow<EngineState> = repo.engineState
    val liveDevices: StateFlow<List<LiveDevice>> = repo.liveDevices
    val lastVerify: StateFlow<VerifyResult?> = repo.lastVerify

    val devices: StateFlow<List<DeviceEntity>> = repo.devicesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _report = MutableStateFlow<ReportSnapshot?>(null)
    val report: StateFlow<ReportSnapshot?> = _report.asStateFlow()

    private val _daily = MutableStateFlow<List<DailyTotal>>(emptyList())
    val daily: StateFlow<List<DailyTotal>> = _daily.asStateFlow()

    private val _capabilities = MutableStateFlow<NetControl.Capabilities?>(null)
    val capabilities: StateFlow<NetControl.Capabilities?> = _capabilities.asStateFlow()

    private val _interfaces = MutableStateFlow<List<DeviceDiscovery.TetherInterface>>(emptyList())
    val interfaces: StateFlow<List<DeviceDiscovery.TetherInterface>> = _interfaces.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _selectedRange = MutableStateFlow(ReportRange.TODAY)
    val selectedRange: StateFlow<ReportRange> = _selectedRange.asStateFlow()

    /** App-wide default billing rate, inherited by newly discovered devices. */
    val defaultPricePerGb: StateFlow<Double> = repo.settings.defaultPricePerGb
    val defaultBillable: StateFlow<Boolean> = repo.settings.defaultBillable

    init {
        refreshReport(ReportRange.TODAY)
        refreshDiagnostics()
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---------------------------------------------------------------------------------------
    // engine control
    // ---------------------------------------------------------------------------------------

    fun startEngine() {
        CollectorService.start(getApplication())
        viewModelScope.launch {
            // Give the service a moment to install rules, then refresh diagnostics.
            kotlinx.coroutines.delay(1_200)
            refreshDiagnostics()
        }
    }

    fun stopEngine() {
        CollectorService.stop(getApplication())
        post("已停止采集")
    }

    fun runSelfTest(seconds: Int = 5) {
        viewModelScope.launch {
            _busy.value = true
            val result = repo.runSelfTest(seconds)
            _busy.value = false
            if (result == null) {
                post("自检失败：引擎未运行", isError = true)
            } else {
                post(
                    if (result.counting) "计数正常，窗口内 +${Fmt.bytes(result.delta)}"
                    else "计数未增长，请检查下方诊断信息",
                    isError = !result.counting,
                )
            }
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            _interfaces.value = repo.tetherInterfaces()
            _capabilities.value = repo.netControl.capabilities()
            refreshDaily()
        }
    }

    fun checkRoot() {
        viewModelScope.launch {
            RootShell.invalidateAvailability()
            val ok = RootShell.isAvailable()
            post(if (ok) "已获得 root 权限" else "未获得 root 权限：${RootShell.lastError() ?: "未知原因"}", isError = !ok)
        }
    }

    // ---------------------------------------------------------------------------------------
    // reporting
    // ---------------------------------------------------------------------------------------

    fun selectRange(range: ReportRange) {
        _selectedRange.value = range
        refreshReport(range)
    }

    fun refreshReport(range: ReportRange = _selectedRange.value) {
        viewModelScope.launch {
            _report.value = repo.report(range)
        }
    }

    private fun refreshDaily() {
        viewModelScope.launch {
            _daily.value = repo.dailyTotals(30)
        }
    }

    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val snapshot = repo.report(_selectedRange.value)
            onReady(repo.toCsv(snapshot))
        }
    }

    // ---------------------------------------------------------------------------------------
    // device mutations
    // ---------------------------------------------------------------------------------------

    fun saveDevice(
        mac: String,
        nickname: String?,
        pricePerGb: Double,
        note: String?,
        billable: Boolean,
        limitDown: Int?,
        limitUp: Int?,
    ) {
        viewModelScope.launch {
            repo.updateBilling(mac, nickname, pricePerGb, note, billable, limitDown, limitUp)
            // Pass the interface the client is currently on, so a USB-tethered machine is shaped on
            // rndis0/usb0 rather than on the Wi-Fi AP (which would not affect it at all).
            val iface = liveDevices.value.firstOrNull { it.device.mac == mac }?.iface
            // If a cap was set or cleared, push it to the kernel; report failures honestly.
            val result = repo.applyRateLimit(mac, limitDown, limitUp, iface)
            if (result.isFailure) {
                post("限速未生效：${result.exceptionOrNull()?.message}", isError = true)
            } else {
                post("已保存设备设置")
            }
            refreshReport()
        }
    }

    fun toggleBlocked(mac: String, blocked: Boolean) {
        viewModelScope.launch {
            val result = repo.setBlocked(mac, blocked)
            if (result.isFailure) {
                post("断网失败：${result.exceptionOrNull()?.message}", isError = true)
            } else {
                post(if (blocked) "已断开该设备" else "已恢复该设备联网")
            }
        }
    }

    fun deleteDevice(mac: String, keepUsage: Boolean) {
        viewModelScope.launch {
            if (keepUsage) {
                // Only detach the counter; historical usage stays for the books.
                repo.applyRateLimit(mac, null, null)
                post("已停止统计该设备（历史记录保留）")
            } else {
                repo.deleteDevice(mac)
                post("已删除该设备及其用量记录")
            }
            refreshReport()
        }
    }

    fun revertAllControls() {
        viewModelScope.launch {
            _busy.value = true
            val log = repo.revertAllControls()
            _busy.value = false
            post(log.joinToString("；").ifEmpty { "没有需要清除的规则" })
            refreshDiagnostics()
        }
    }

    /**
     * Saves the app-wide default rate.
     *
     * @param applyToExisting when true, also prices every device that has no price yet. Devices the
     *   user has already priced are left untouched.
     */
    fun saveDefaultPrice(pricePerGb: Double, billable: Boolean, applyToExisting: Boolean) {
        viewModelScope.launch {
            repo.setDefaultPricePerGb(pricePerGb)
            repo.setDefaultBillable(billable)
            if (applyToExisting && pricePerGb > 0.0) {
                val changed = repo.applyDefaultPriceToUnpriced()
                post(
                    if (changed > 0) "默认单价已保存，并为 $changed 台未定价设备套用"
                    else "默认单价已保存（没有未定价的设备需要套用）"
                )
            } else {
                post(
                    if (pricePerGb > 0.0) "默认单价已保存，新接入设备将自动套用"
                    else "默认单价已清除"
                )
            }
            refreshReport()
        }
    }

    fun purgeOldUsage(days: Int) {
        viewModelScope.launch {
            val n = repo.forgetOldUsage(days)
            post("已清理 $n 条历史分片（早于 $days 天）")
            refreshReport()
        }
    }

    private fun post(text: String, isError: Boolean = false) {
        _message.value = UiMessage(text, isError)
    }
}
