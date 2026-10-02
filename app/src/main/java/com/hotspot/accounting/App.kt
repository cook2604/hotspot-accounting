package com.hotspot.accounting

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("HotspotAccounting", "application created")
    }
}
