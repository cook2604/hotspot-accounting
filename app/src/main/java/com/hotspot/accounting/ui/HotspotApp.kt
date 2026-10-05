package com.hotspot.accounting.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hotspot.accounting.ui.glass.GlassBackdrop
import com.hotspot.accounting.ui.glass.GlassBottomBar
import com.hotspot.accounting.ui.glass.GlassTab

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("实时", Icons.Filled.Wifi),
    HISTORY("统计", Icons.Filled.History),
    SETTINGS("设置", Icons.Filled.Settings),
}

/**
 * Root of the UI.
 *
 * The layout is intentionally not a `Scaffold` with a Material bottom bar. The glass design needs the
 * backdrop to extend edge to edge *behind* the tab bar, so the bar is placed as a floating overlay in
 * a `Box` rather than as a docked slot that would cut the backdrop off at its top edge. That also lets
 * content scroll visibly under the translucent material, which is what makes it read as glass.
 */
@Composable
fun HotspotApp(viewModel: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.text)
            viewModel.consumeMessage()
        }
    }

    GlassBackdrop(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // Transparent so the gradient backdrop shows through every layer above it.
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
            ) {
                // Padding at the bottom reserves room for the floating bar so the last item of a list
                // is never hidden behind it.
                Box(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                    when (Tab.entries[tab]) {
                        Tab.LIVE -> LiveScreen(
                            viewModel = viewModel,
                            onExportCsv = { csv -> shareCsv(context, csv) },
                        )
                        Tab.HISTORY -> HistoryScreen(
                            viewModel = viewModel,
                            onExportCsv = { csv -> shareCsv(context, csv) },
                        )
                        Tab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                    }
                }

                GlassBottomBar(
                    tabs = Tab.entries.map { GlassTab(it.label, it.icon) },
                    selectedIndex = tab,
                    onSelect = { tab = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }
    }
}

/** Hands the CSV to whatever app the user picks, without needing storage permissions. */
private fun shareCsv(context: android.content.Context, csv: String) {
    val file = java.io.File(context.cacheDir, "hotspot-usage.csv")
    runCatching { file.writeText(csv, Charsets.UTF_8) }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "热点流量统计")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "导出 CSV"))
}
