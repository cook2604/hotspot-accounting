package com.hotspot.accounting

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import com.hotspot.accounting.ui.HotspotApp
import com.hotspot.accounting.ui.theme.HotspotAccountingTheme

class MainActivity : ComponentActivity() {

    /** Android 13+ requires an explicit grant before we can show the ongoing notification. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            HotspotAccountingTheme {
                // Transparent, not colour-filled: HotspotApp paints its own gradient backdrop and the
                // glass panes need to composite against it. An opaque Surface here would sit on top
                // of that backdrop and flatten every translucent layer.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                ) {
                    HotspotApp()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
@Suppress("unused")
private fun PreviewAnchor() {
    // Keeps the theme import meaningful for previews added later.
}
