package com.hotspot.accounting.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Resumes collection after a reboot.
 *
 * Billing accuracy depends on the counters running whenever the hotspot is up, so the engine is
 * restarted automatically. It is harmless when the hotspot is off: the engine simply installs its
 * rules and reports zero devices.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "boot completed, starting collector")
        runCatching { CollectorService.start(context) }
            .onFailure { Log.e(TAG, "failed to start collector at boot: ${it.message}") }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
