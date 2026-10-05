package com.hotspot.accounting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspot.accounting.data.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val verify by viewModel.lastVerify.collectAsStateWithLifecycle()
    val caps by viewModel.capabilities.collectAsStateWithLifecycle()
    val interfaces by viewModel.interfaces.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val defaultPrice by viewModel.defaultPricePerGb.collectAsStateWithLifecycle()
    val defaultBillable by viewModel.defaultBillable.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置与诊断") })

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DefaultPriceCard(
                currentPrice = defaultPrice,
                currentBillable = defaultBillable,
                onSave = { price, billable, applyToExisting ->
                    viewModel.saveDefaultPrice(price, billable, applyToExisting)
                },
            )

            SectionCard("运行状态") {
                KeyValue("引擎", when (val e = engine) {
                    is EngineState.Running -> "运行中"
                    is EngineState.Failed -> "失败"
                    EngineState.Starting -> "启动中"
                    EngineState.Idle -> "已停止"
                })
                if (engine is EngineState.Running) {
                    KeyValue("计数后端", (engine as EngineState.Running).backend)
                }
                if (engine is EngineState.Failed) {
                    KeyValue("错误", (engine as EngineState.Failed).message, error = true)
                }
                KeyValue(
                    "共享接口",
                    interfaces.joinToString { "${it.name}（${it.transport.label}）" }.ifEmpty { "未识别" },
                    mono = true,
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.runSelfTest(5) }, enabled = !busy) {
                        Text("运行计数自检")
                    }
                    OutlinedButton(onClick = { viewModel.checkRoot() }, enabled = !busy) {
                        Text("测试 root")
                    }
                }
            }

            if (verify != null) {
                SectionCard("最近一次自检") {
                    KeyValue(
                        "结果",
                        if (verify!!.counting) "计数器正常增长" else "计数器未增长",
                        error = !verify!!.counting,
                    )
                    KeyValue("窗口内增量", Fmt.bytes(verify!!.delta))
                    KeyValue("已装计数器", "${verify!!.deviceCount} 台")
                    verify!!.notes.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard("网络控制能力") {
                val c = caps
                if (c == null) {
                    Text("正在检测…", style = MaterialTheme.typography.bodySmall)
                } else {
                    KeyValue("断网（按 MAC）", if (c.canBlock) "可用" else "不可用",
                        error = !c.canBlock)
                    KeyValue("限速", if (c.canRateLimit) "可用" else "不可用",
                        error = !c.canRateLimit)
                    KeyValue("tc", if (c.hasTc) "已找到" else "缺失")
                    KeyValue("ebtables", if (c.hasEbtables) "已找到" else "缺失")
                    KeyValue("ifb 模块", if (c.ifbSupported) "可用" else "不可用")
                    c.notes.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.revertAllControls() },
                        enabled = !busy,
                    ) {
                        Text("清除全部限速 / 断网规则")
                    }
                }
            }

            SectionCard("历史数据") {
                Text(
                    "用量按小时分片存储。清理旧数据可缩小数据库，但会永久删除对应期间的账目明细。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.purgeOldUsage(90) }) { Text("清理 90 天前") }
                    OutlinedButton(onClick = { viewModel.purgeOldUsage(365) }) { Text("清理 1 年前") }
                }
            }

            SectionCard("工作原理") {
                Text(
                    "本应用通过 root 权限在内核中安装按 MAC 地址的流量计数器，" +
                        "因此统计的是真正经过热点的字节数，而不是估算值。\n\n" +
                        "· 计数发生在网桥层的 prerouting，分别统计上行与下行。\n" +
                        "· 以 MAC 作为设备身份，客户端续租换 IP 不会造成账目错位。\n" +
                        "· 计数器清零（重启热点或重启手机）会被自动识别，不会重复计费。\n" +
                        "· 部分手机的「随机 MAC」功能会让同一台设备换 MAC 后变成新记录，" +
                        "命名一次即可，后续可在本页重命名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * App-wide default billing rate.
 *
 * Setting a price per device individually does not scale when every client is charged the same rate,
 * so this is the single place to configure it. Two behaviours are deliberately separated:
 *  - saving alone only affects *future* devices (nothing already recorded changes);
 *  - "套用" additionally prices devices that have no price yet, and never touches a device the user
 *    has already priced by hand.
 */
@Composable
private fun DefaultPriceCard(
    currentPrice: Double,
    currentBillable: Boolean,
    onSave: (price: Double, billable: Boolean, applyToExisting: Boolean) -> Unit,
) {
    var priceText by remember(currentPrice) {
        mutableStateOf(if (currentPrice > 0.0) trimTrailingZeros(currentPrice) else "")
    }
    var billable by remember(currentBillable) { mutableStateOf(currentBillable) }
    var applyToExisting by remember { mutableStateOf(false) }

    val parsedPrice = priceText.toDoubleOrNull() ?: 0.0
    val valid = priceText.isBlank() || (parsedPrice >= 0.0 && parsedPrice.isFinite())

    SectionCard("默认单价（元 / GB）") {
        Text(
            "设一次，之后新接入的设备自动按这个价格计费，不用一台一台填。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = priceText,
            // Only digits and a single decimal point, so the field cannot hold an unparseable value.
            onValueChange = { raw ->
                priceText = raw.filter { it.isDigit() || it == '.' }
                    .let { filtered ->
                        val firstDot = filtered.indexOf('.')
                        if (firstDot < 0) filtered
                        else filtered.substring(0, firstDot + 1) +
                            filtered.substring(firstDot + 1).replace(".", "")
                    }
            },
            label = { Text("每 GB 价格") },
            placeholder = { Text("例如 3 表示 3 元/GB；留空表示不计费") },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                Text(
                    when {
                        !valid -> "请输入有效的非负数字"
                        parsedPrice > 0.0 -> "1 GB 收 ¥${Fmt.money(parsedPrice)}；10 GB 收 ¥${Fmt.money(parsedPrice * 10)}"
                        else -> "未设置默认单价，新设备不会自动计费"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = billable, onCheckedChange = { billable = it })
            Spacer(Modifier.width(8.dp))
            Text("新设备纳入计费统计", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = applyToExisting, onCheckedChange = { applyToExisting = it })
            Spacer(Modifier.width(8.dp))
            Text(
                "同时套用到当前未定价的设备",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "已手动设过价的设备不会被覆盖。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onSave(parsedPrice, billable, applyToExisting) },
            enabled = valid,
        ) {
            Text("保存默认单价")
        }
    }
}

/** Formats a price without a trailing ".0", so "3.0" reads as "3". */
private fun trimTrailingZeros(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, error: Boolean = false, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
