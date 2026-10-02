package com.hotspot.accounting.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Formatting helpers shared by the screens. */
object Fmt {

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    /** Human-readable byte size with a fixed, sensible number of decimals. */
    fun bytes(value: Long): String {
        val v = value.toDouble()
        return when {
            abs(v) >= TB -> String.format(Locale.US, "%.2f TB", v / TB)
            abs(v) >= GB -> String.format(Locale.US, "%.2f GB", v / GB)
            abs(v) >= MB -> String.format(Locale.US, "%.1f MB", v / MB)
            abs(v) >= KB -> String.format(Locale.US, "%.0f KB", v / KB)
            else -> "$value B"
        }
    }

    /** Gigabytes as a plain number, for billing arithmetic display. */
    fun gb(value: Long): String = String.format(Locale.US, "%.3f", value / GB)

    fun gbValue(value: Long): Double = value / GB

    fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

    fun rate(kbps: Int?): String = if (kbps == null || kbps <= 0) "不限" else "$kbps kbps"

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("MM-dd", Locale.getDefault())

    fun time(ts: Long): String = timeFmt.format(Date(ts))
    fun day(ts: Long): String = dayFmt.format(Date(ts))

    /** Coarse "x ago" text, which reads better than a raw timestamp in a live list. */
    fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - ts).coerceAtLeast(0) / 1000
        return when {
            diff < 60 -> "${diff}秒前"
            diff < 3600 -> "${diff / 60}分钟前"
            diff < 86_400 -> "${diff / 3600}小时前"
            else -> "${diff / 86_400}天前"
        }
    }

    /** Rounds a byte count to the nearest kilobyte, for compact display. */
    fun kb(value: Long): Long = (value / 1024.0).roundToLong()
}
