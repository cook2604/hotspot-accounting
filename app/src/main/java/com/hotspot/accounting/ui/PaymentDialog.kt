package com.hotspot.accounting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hotspot.accounting.data.DeviceLedger
import com.hotspot.accounting.data.PaymentEntity
import com.hotspot.accounting.ui.glass.GlassDivider
import com.hotspot.accounting.ui.theme.GlassPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Payments and balance for one device.
 *
 * Layout note: the amount field is the first thing under the balance, because in practice the user
 * opens this screen to record money just received and should not have to hunt for the field.
 */
@Composable
fun PaymentDialog(
    deviceName: String,
    ledger: DeviceLedger?,
    payments: List<PaymentEntity>,
    todayCharge: Double,
    onDismiss: () -> Unit,
    onRecord: (amount: Double, paidAt: Long, note: String?) -> Unit,
    onWriteOff: (amount: Double) -> Unit,
    onDeletePayment: (Long) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmWriteOff by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val parsed = amountText.toDoubleOrNull()
    val valid = parsed != null && parsed > 0.0

    if (confirmWriteOff) {
        AlertDialog(
            onDismissRequest = { confirmWriteOff = false },
            title = { Text("销账确认") },
            text = {
                Text(
                    "将把该设备的未结余额记为「已免除」，余额归零。\n" +
                        "这是记账操作，不会修改已产生的用量记录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val outstanding = ledger?.outstanding ?: 0.0
                    if (outstanding > 0.0) onWriteOff(outstanding)
                    confirmWriteOff = false
                }) { Text("确认销账", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWriteOff = false }) { Text("取消") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收款 · $deviceName") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // --- money position ---
                val charged = ledger?.charged ?: 0.0
                val paid = ledger?.paid ?: 0.0
                val writtenOff = ledger?.writtenOff ?: 0.0
                val outstanding = ledger?.outstanding ?: 0.0

                MoneyRow("累计应收", "¥" + Fmt.money(charged))
                if (writtenOff > 0.005) {
                    MoneyRow("已免除", "¥" + Fmt.money(writtenOff))
                }
                MoneyRow("已收款", "¥" + Fmt.money(paid), valueColor = GlassPalette.Success)
                Spacer(Modifier.height(4.dp))
                GlassDivider()
                Spacer(Modifier.height(6.dp))
                MoneyRow(
                    label = if (outstanding < -0.005) "客户余额（多付）" else "尚欠",
                    value = "¥" + Fmt.money(kotlin.math.abs(outstanding)),
                    valueColor = when {
                        outstanding > 0.005 -> MaterialTheme.colorScheme.error
                        outstanding < -0.005 -> GlassPalette.Success
                        else -> GlassPalette.Success
                    },
                    bold = true,
                )
                if (ledger != null && ledger.carriedDebt > 0.005) {
                    Text(
                        "其中 ¥${Fmt.money(ledger.carriedDebt)} 为上次遗留欠款",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (todayCharge > 0.0) {
                    Text(
                        "本次会话新增 ¥${Fmt.money(todayCharge)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // --- record a payment ---
                Text("记录收款", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        amountText = raw.filter { it.isDigit() || it == '.' }
                            .let { f ->
                                val first = f.indexOf('.')
                                if (first < 0) f
                                else f.substring(0, first + 1) +
                                    f.substring(first + 1).replace(".", "")
                            }
                    },
                    label = { Text("收款金额（元）") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        if (outstanding > 0.005) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { amountText = Fmt.money(outstanding) }) {
                                    Text("全额 ¥${Fmt.money(outstanding)}")
                                }
                                TextButton(onClick = {
                                    amountText = Fmt.money(outstanding / 2)
                                }) { Text("一半") }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("例如：微信、先付一半") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val amt = parsed ?: return@TextButton
                        // Recorded at the current instant. The user can adjust history from the list
                        // below if they are entering an older payment.
                        onRecord(amt, System.currentTimeMillis(), note.takeIf { it.isNotBlank() })
                        amountText = ""
                        note = ""
                    },
                    enabled = valid,
                ) { Text("确认收款") }

                // --- history ---
                if (payments.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    GlassDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "收款记录（${payments.size} 笔）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    payments.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    dateFmt.format(Date(p.paidAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!p.note.isNullOrBlank()) {
                                    Text(
                                        p.note!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                (if (p.amount < 0) "-¥" else "¥") + Fmt.money(kotlin.math.abs(p.amount)),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (p.amount < 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else GlassPalette.Success,
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { onDeletePayment(p.id) }) {
                                Text("删", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { confirmWriteOff = true }) {
                    Text("将未结余额全部销账", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MoneyRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    bold: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (bold) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor,
        )
    }
}

/**
 * Prompt shown when a device returns after a long absence still owing money.
 *
 * This exists to prevent old debt from silently merging with a new session's usage. The user is asked
 * to decide explicitly, because only they know whether the previous amount was settled in cash, is
 * still owed, or should be forgiven.
 */
@Composable
fun BillingReviewDialog(
    deviceName: String,
    outstanding: Double,
    awayDescription: String,
    onKeepDebt: () -> Unit,
    onSettleNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回归设备有待结算账目") },
        text = {
            Column {
                Text(
                    "「$deviceName」$awayDescription 后重新连接，当前尚欠 " +
                        "¥${Fmt.money(outstanding)}。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "请选择如何处理旧账，以免和这次的新用量混在一起：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "· 「保留欠款」——旧账单独标注为「遗留欠款」，与本次新增用量分开显示\n" +
                        "· 「现在结清」——记一笔等额收款，余额归零，重新开始",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepDebt) { Text("保留欠款") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSettleNow) { Text("现在结清") }
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}
