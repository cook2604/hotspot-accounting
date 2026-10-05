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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspot.accounting.data.DeviceUsageRow
import com.hotspot.accounting.data.ReportRange
import com.hotspot.accounting.ui.glass.GlassChip
import com.hotspot.accounting.ui.glass.GlassLargeTitle
import com.hotspot.accounting.ui.glass.GlassMaterial
import com.hotspot.accounting.ui.glass.GlassPane
import com.hotspot.accounting.ui.theme.GlassPalette

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onExportCsv: (String) -> Unit,
) {
    val range by viewModel.selectedRange.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val daily by viewModel.daily.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        GlassLargeTitle(
            title = "用量与账目",
            subtitle = "按区间汇总每台设备的用量与应收",
            actions = {
                IconButton(onClick = { viewModel.exportCsv(onExportCsv) }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "导出当前区间 CSV")
                }
            },
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ReportRange.entries.toList()) { r ->
                GlassChip(
                    label = r.label,
                    selected = r == range,
                    onClick = { viewModel.selectRange(r) },
                )
            }
        }

        val snapshot = report
        if (snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在读取…")
            }
            return@Column
        }

        // Totals
        GlassPane(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            material = GlassMaterial.THICK,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("区间总用量", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.bytes(snapshot.totalBytes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Text("${Fmt.gb(snapshot.totalBytes)} GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("应收合计", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("¥" + Fmt.money(snapshot.totalCharge),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = GlassPalette.Money)
                    Text("${snapshot.rows.count { it.pricePerGb > 0 }} 台已设单价",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (snapshot.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该区间内没有用量记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val maxTotal = snapshot.rows.maxOf { it.total }.coerceAtLeast(1L)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(snapshot.rows, key = { it.mac }) { row ->
                    UsageRow(row = row, maxTotal = maxTotal)
                }

                if (daily.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("近 30 天趋势", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        DailySparkline(daily.map { it.total })
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(row: DeviceUsageRow, maxTotal: Long) {
    GlassPane(
        modifier = Modifier.fillMaxWidth(),
        material = GlassMaterial.REGULAR,
        contentPadding = 12.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.displayName, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(row.mac, style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.bytes(row.total), fontWeight = FontWeight.Bold)
                    if (row.pricePerGb > 0) {
                        Text(
                            "¥" + Fmt.money(row.charge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (row.total.toFloat() / maxTotal.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("↑ ${Fmt.bytes(row.txBytes)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("↓ ${Fmt.bytes(row.rxBytes)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Fmt.gb(row.total)} GB", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Minimal bar chart drawn with plain layouts, avoiding a charting dependency. */
@Composable
private fun DailySparkline(values: List<Long>) {
    if (values.isEmpty()) return
    val max = values.max().coerceAtLeast(1L)
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        values.forEach { v ->
            val fraction = (v.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .height((4 + fraction * 64).dp)
                    .background(
                        if (v == 0L) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
            )
        }
    }
}
