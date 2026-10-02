package com.hotspot.accounting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hotspot.accounting.core.LiveDevice

/**
 * Edits a device's identity, billing rate, and rate caps.
 *
 * Rate caps are free-text because a numeric field that silently coerces "0" to "unlimited" is
 * confusing; instead we parse explicitly and tell the user which interpretation we used.
 */
@Composable
fun DeviceEditorDialog(
    device: LiveDevice,
    defaultPricePerGb: Double = 0.0,
    onDismiss: () -> Unit,
    onSave: (nickname: String?, pricePerGb: Double, note: String?, billable: Boolean, down: Int?, up: Int?) -> Unit,
    onDelete: (keepUsage: Boolean) -> Unit,
) {
    val d = device.device
    var nickname by remember { mutableStateOf(d.nickname.orEmpty()) }
    // An unpriced device shows the app-wide default so the user can see what it will be charged,
    // rather than an empty box that hides the effective rate.
    var price by remember {
        mutableStateOf(
            when {
                d.pricePerGb > 0 -> d.pricePerGb.toString()
                defaultPricePerGb > 0 -> defaultPricePerGb.toString()
                else -> ""
            }
        )
    }
    var note by remember { mutableStateOf(d.billingNote.orEmpty()) }
    var billable by remember { mutableStateOf(d.billable) }
    var down by remember { mutableStateOf(d.limitDownKbps?.toString().orEmpty()) }
    var up by remember { mutableStateOf(d.limitUpKbps?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除设备") },
            text = {
                Text(
                    "「停止统计」会移除该设备的计数器但保留已记录的历史用量，适合之后还要对账。\n\n" +
                        "「彻底删除」会连同历史流量记录一起删除，不可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(false) }) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onDelete(true) }) { Text("停止统计") }
                    TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备设置") },
        text = {
            Column {
                Text(
                    d.mac,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                d.hostname?.let {
                    Text(
                        "主机名：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("备注名称") },
                    placeholder = { Text("例如：张三的手机") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("单价（元 / GB）") },
                    placeholder = { Text("留空或 0 表示不计费") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        val p = price.toDoubleOrNull() ?: 0.0
                        Text(
                            when {
                                p > 0.0 -> "该设备已用 ${Fmt.gb(device.total)} GB，按此价应收 ¥${Fmt.money(p * Fmt.gbValue(device.total))}"
                                defaultPricePerGb > 0.0 -> "默认单价 ¥${Fmt.money(defaultPricePerGb)}/GB，可在此单独覆盖"
                                else -> "可在「设置」页配置默认单价，避免逐台填写"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("账务备注") },
                    placeholder = { Text("例如：7 月已结清") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = billable, onCheckedChange = { billable = it })
                    Spacer(Modifier.width(8.dp))
                    Text("纳入计费统计")
                }

                Spacer(Modifier.height(12.dp))
                Text("限速（kbps，留空表示不限）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = down,
                        onValueChange = { down = it.filter(Char::isDigit) },
                        label = { Text("下载") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = up,
                        onValueChange = { up = it.filter(Char::isDigit) },
                        label = { Text("上传") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "限速会改写系统网络队列，若提示与系统队列冲突请先重启一次热点。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    nickname.takeIf { it.isNotBlank() },
                    price.toDoubleOrNull() ?: 0.0,
                    note.takeIf { it.isNotBlank() },
                    billable,
                    down.toIntOrNull()?.takeIf { it > 0 },
                    up.toIntOrNull()?.takeIf { it > 0 },
                )
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
