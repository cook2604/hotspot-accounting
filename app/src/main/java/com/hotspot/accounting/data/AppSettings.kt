package com.hotspot.accounting.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide settings that are not per-device.
 *
 * Backed by SharedPreferences rather than a Room table on purpose: these are a handful of scalar
 * preferences, and putting them in the database would mean bumping its schema version and risking a
 * destructive migration over a settings row. SharedPreferences needs no dependency and cannot
 * endanger the usage history, which is the data that actually matters here.
 *
 * Only [defaultPricePerGb] exists today: the billing rate that newly discovered devices inherit so
 * the user does not have to type the same price once per device.
 */
class AppSettings private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _defaultPricePerGb = MutableStateFlow(prefs.getFloat(KEY_DEFAULT_PRICE, 0f).toDouble())

    /**
     * Billing rate applied to newly discovered devices, in currency units per gigabyte.
     *
     * Zero means "no default", i.e. new devices start unbilled until the user sets a price. Zero is
     * also a legitimate per-device value, so [setDefaultPricePerGb] treats 0 as "unset" and the
     * collector simply skips the assignment.
     */
    val defaultPricePerGb: StateFlow<Double> = _defaultPricePerGb.asStateFlow()

    /** Whether new devices should be billable at all once a default price exists. */
    private val _defaultBillable = MutableStateFlow(prefs.getBoolean(KEY_DEFAULT_BILLABLE, true))
    val defaultBillable: StateFlow<Boolean> = _defaultBillable.asStateFlow()

    fun setDefaultPricePerGb(value: Double) {
        val coerced = if (value.isFinite() && value >= 0.0) value else 0.0
        prefs.edit().putFloat(KEY_DEFAULT_PRICE, coerced.toFloat()).apply()
        _defaultPricePerGb.value = coerced
        Log.i(TAG, "default price per GB = $coerced")
    }

    fun setDefaultBillable(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEFAULT_BILLABLE, value).apply()
        _defaultBillable.value = value
    }

    companion object {
        private const val TAG = "AppSettings"
        private const val PREFS_NAME = "hotspot_accounting_settings"
        private const val KEY_DEFAULT_PRICE = "default_price_per_gb"
        private const val KEY_DEFAULT_BILLABLE = "default_billable"

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Billing arithmetic, kept in exactly one place.
 *
 * The live screen and the history report both show money, and if each computed it separately they
 * could disagree by a rounding step. Everything routes through [chargeFor] so a price change or a
 * rounding decision applies everywhere at once.
 */
object Billing {

    /** Bytes in a gigabyte. Binary, matching how Android reports data usage to users. */
    const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    /** Gigabytes (binary) represented by [bytes]. */
    fun gbFor(bytes: Long): Double = bytes / BYTES_PER_GB

    /**
     * Amount owed for [bytes] at [pricePerGb].
     *
     * Returns 0 when billing does not apply, so callers never have to special-case "unpriced".
     * The result is not rounded here; rounding belongs at the point of display so intermediate
     * sums stay exact.
     */
    fun chargeFor(bytes: Long, pricePerGb: Double, billable: Boolean = true): Double =
        if (!billable || pricePerGb <= 0.0 || !pricePerGb.isFinite()) 0.0
        else gbFor(bytes) * pricePerGb
}
