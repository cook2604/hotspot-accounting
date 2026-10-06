package com.hotspot.accounting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspot.accounting.core.CounterManager
import com.hotspot.accounting.core.DeviceDiscovery
import com.hotspot.accounting.core.LiveDevice
import com.hotspot.accounting.data.Billing
import com.hotspot.accounting.data.DeviceLedger
import com.hotspot.accounting.data.EngineState
import com.hotspot.accounting.ui.glass.GlassDivider
import com.hotspot.accounting.ui.glass.GlassLargeTitle
import com.hotspot.accounting.ui.glass.GlassMaterial
import com.hotspot.accounting.ui.glass.GlassPane
import com.hotspot.accounting.ui.theme.GlassPalette

@Composable
fun LiveScreen(
    viewModel: MainViewModel,
    onExportCsv: (String) -> Unit,
) {
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val live by viewModel.liveDevices.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val defaultPrice by viewModel.defaultPricePerGb.collectAsStateWithLifecycle()
    val ledgers by viewModel.ledgers.collectAsStateWithLifecycle()
    val openPayments by viewModel.openPayments.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<LiveDevice?>(null) }
    var paying by remember { mutableStateOf<LiveDevice?>(null) }

    // Recompute balances whenever the device list changes, so a balance shown next to a card is
    // never stale relative to the usage beside it.
    LaunchedEffect(live.size) { viewModel.refreshLedgers() }

    val online = live.count { it.online }
    val sessionTotal = live.sumOf { it.total }
    val rateBytesPerSec = live.sumOf { it.delta } / (com.hotspot.accounting.core.CounterManager.DEFAULT_INTERVAL_MS / 1000.0)

    // Session charges use the same Billing helper as the history report, so the two can never
    // disagree about how money is computed.
    val sessionCharge = live.sumOf { item ->
        Billing.chargeFor(item.total, item.device.pricePerGb, item.device.billable)
    }
    val pricedDeviceCount = live.count { it.device.pricePerGb > 0.0 }

    Column(Modifier.fillMaxSize()) {
        GlassLargeTitle(
            title = "热点分账",
            subtitle = "按 MAC 统计每台设备的真实用量",
            actions = {
                IconButton(onClick = { viewModel.runSelfTest(5) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "计数自检")
                }
                IconButton(onClick = { viewModel.exportCsv(onExportCsv) }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "导出 CSV")
                }
            },
        )

        EngineBanner(engine = engine, busy = busy, viewModel = viewModel)

        SummaryRow(
            onlineCount = online,
            totalCount = live.size,
            sessionBytes = sessionTotal,
            bytesPerSec = rateBytesPerSec,
            sessionCharge = sessionCharge,
            pricedDeviceCount = pricedDeviceCount,
        )

        HorizontalDivider()

        if (live.isEmpty()) {
            EmptyHint(engine)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(live, key = { it.device.mac }) { item ->
                    DeviceCard(
                        item = item,
                        ledger = ledgers[item.device.mac],
                        onEdit = { editing = item },
                        onPayments = {
                            viewModel.loadPayments(item.device.mac)
                            paying = item
                        },
                        onToggleBlock = {
                            viewModel.toggleBlocked(item.device.mac, !item.device.blocked)
                        },
                    )
                }
            }
        }
    }

    editing?.let { item ->
        DeviceEditorDialog(
            device = item,
            defaultPricePerGb = defaultPrice,
            onDismiss = { editing = null },
            onSave = { nickname, price, note, billable, down, up ->
                viewModel.saveDevice(item.device.mac, nickname, price, note, billable, down, up)
                editing = null
            },
            onDelete = { keepUsage ->
                viewModel.deleteDevice(item.device.mac, keepUsage)
                editing = null
            },
        )
    }

    paying?.let { item ->
        PaymentDialog(
            deviceName = item.device.displayName,
            ledger = ledgers[item.device.mac],
            payments = openPayments,
            // Charge accrued during the current session, shown alongside the lifetime balance so the
            // two are never confused with each other.
            todayCharge = Fmt.gbValue(item.total) * item.device.pricePerGb,
            onDismiss = {
                viewModel.clearOpenPayments()
                paying = null
            },
            onRecord = { amount, paidAt, note ->
                viewModel.recordPayment(item.device.mac, amount, paidAt, note)
            },
            onWriteOff = { amount -> viewModel.writeOff(item.device.mac, amount) },
            onDeletePayment = { id -> viewModel.deletePayment(item.device.mac, id) },
        )
    }
}

@Composable
private fun EngineBanner(engine: EngineState, busy: Boolean, viewModel: MainViewModel) {
    val (bg, fg, title, detail) = when (engine) {
        is EngineState.Running -> Quad(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "正在统计",
            engine.backend,
        )
        is EngineState.Failed -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "启动失败",
            engine.message,
        )
        EngineState.Starting -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "正在启动…",
            "正在申请 root 并安装计数规则",
        )
        EngineState.Idle -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "未运行",
            "点击右侧按钮开始统计",
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = fg)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = fg)
            }
            if (engine is EngineState.Running) {
                FilledTonalButton(onClick = { viewModel.stopEngine() }, enabled = !busy) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            } else {
                FilledTonalButton(onClick = { viewModel.startEngine() }, enabled = !busy) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("开始")
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun SummaryRow(
    onlineCount: Int,
    totalCount: Int,
    sessionBytes: Long,
    bytesPerSec: Double,
    sessionCharge: Double,
    pricedDeviceCount: Int,
) {
    // A 2x2 grid instead of a single row: four figures in one row are cramped on a phone, and the
    // glass pane needs enough height for its rim to read as a distinct surface.
    GlassPane(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        material = GlassMaterial.REGULAR,
        contentPadding = 14.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("在线设备", "$onlineCount", "共 $totalCount 台", Modifier.weight(1f))
                Stat("本次会话", Fmt.bytes(sessionBytes), "自规则安装起", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat(
                    "实时速率",
                    Fmt.bytes(bytesPerSec.toLong()) + "/s",
                    "合计",
                    Modifier.weight(1f),
                )
                Stat(
                    label = "应收合计",
                    value = if (pricedDeviceCount > 0) "¥" + Fmt.money(sessionCharge) else "—",
                    hint = if (pricedDeviceCount > 0) "$pricedDeviceCount 台已定价" else "未设单价",
                    modifier = Modifier.weight(1f),
                    valueColor = GlassPalette.Money,
                )
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = valueColor)
        Text(hint, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(engine: EngineState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(
                if (engine is EngineState.Running) "暂无设备连接" else "统计尚未开始",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (engine is EngineState.Running) {
                    "让设备连上本机热点后，它会自动出现在这里。\n" +
                        "若设备已连接却看不到，请到「设置」页运行自检。"
                } else {
                    "点上方「开始」授权 root 后即可统计。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    item: LiveDevice,
    ledger: DeviceLedger?,
    onEdit: () -> Unit,
    onPayments: () -> Unit,
    onToggleBlock: () -> Unit,
) {
    val d = item.device
    GlassPane(
        modifier = Modifier.fillMaxWidth(),
        // Offline devices get a thinner, dimmer material so the online ones stand out without
        // resorting to a solid colour change, which would break the translucency.
        material = if (item.online) GlassMaterial.REGULAR else GlassMaterial.ULTRA_THIN,
        accent = if (item.online) Color.Transparent
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        contentPadding = 12.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (item.online) Color(0xFF2ECC71) else Color(0xFF9E9E9E))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildString {
                            d.vendor?.let { append(it).append(" · ") }
                            append(d.mac)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // How this client is attached. Shown because a desktop on USB tethering and a
                    // phone on the Wi-Fi hotspot behave differently and are shaped on different links.
                    if (item.online && item.transport != DeviceDiscovery.Transport.UNKNOWN) {
                        Text(
                            buildString {
                                append(item.transport.label)
                                item.iface?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (d.blocked) {
                    Icon(
                        Icons.Filled.Block,
                        contentDescription = "已断网",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (d.pricePerGb > 0) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = "计费中",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("上传", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.bytes(item.txBytes), style = MaterialTheme.typography.bodyMedium)
                }
                Column {
                    Text("下载", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.bytes(item.rxBytes), style = MaterialTheme.typography.bodyMedium)
                }
                Column {
                    Text("合计", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        Fmt.bytes(item.total),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (d.pricePerGb > 0) {
                    Column {
                        Text("应收", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "¥" + Fmt.money(Fmt.gbValue(item.total) * d.pricePerGb),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Money position, when this device is billed. Shown above the raw byte figures because
            // "how much does he owe" is the question the operator actually has.
            if (ledger != null && (ledger.charged > 0.005 || ledger.paid > 0.005)) {
                GlassDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            if (ledger.inCredit) "客户余额" else "尚欠",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "¥" + Fmt.money(kotlin.math.abs(ledger.outstanding)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                ledger.inCredit -> GlassPalette.Success
                                ledger.outstanding > 0.005 -> MaterialTheme.colorScheme.error
                                else -> GlassPalette.Success
                            },
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("累计应收 / 已收", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "¥${Fmt.money(ledger.charged)} / ¥${Fmt.money(ledger.paid)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (ledger.lastPaymentAt != null) {
                            Text(
                                "上次收款 " + Fmt.ago(ledger.lastPaymentAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (ledger.carriedDebt > 0.005) {
                    Text(
                        "含上次遗留欠款 ¥${Fmt.money(ledger.carriedDebt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlassPalette.Warning,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        d.lastIp?.let { append(it).append(" · ") }
                        append("最后活动 ").append(Fmt.ago(d.lastSeen))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onToggleBlock) {
                    Icon(Icons.Filled.Block, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (d.blocked) "恢复" else "断网")
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onPayments) {
                    Text("收款")
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("设置")
                }
            }
        }
    }
}
