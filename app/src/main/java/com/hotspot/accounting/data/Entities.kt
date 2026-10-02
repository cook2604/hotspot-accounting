package com.hotspot.accounting.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A device that has ever connected to our hotspot, keyed by MAC address.
 *
 * MAC is the identity rather than IP because it is the only identifier that is stable across DHCP
 * renewals, and it is what per-device billing has to be anchored to.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "mac") val mac: String,

    /** User-assigned label, e.g. "张三的手机". Null means unnamed. */
    @ColumnInfo(name = "nickname") val nickname: String? = null,

    /** Last hostname reported via DHCP/DNS, useful before the user names the device. */
    @ColumnInfo(name = "hostname") val hostname: String? = null,

    /** Last known IPv4 address. Advisory only; changes across leases. */
    @ColumnInfo(name = "last_ip") val lastIp: String? = null,

    /** Vendor resolved from the MAC OUI, when known. */
    @ColumnInfo(name = "vendor") val vendor: String? = null,

    /** Price in currency units per gigabyte. Zero means "not billed". */
    @ColumnInfo(name = "price_per_gb", defaultValue = "0") val pricePerGb: Double = 0.0,

    /**
     * Free-form billing note, e.g. "包月已付" or "按量结算".
     */
    @ColumnInfo(name = "billing_note") val billingNote: String? = null,

    /** Whether usage for this device should accrue charges at all. */
    @ColumnInfo(name = "billable", defaultValue = "1") val billable: Boolean = true,

    /** Optional per-device download rate cap in kbit/s; null = unlimited. */
    @ColumnInfo(name = "limit_down_kbps") val limitDownKbps: Int? = null,

    /** Optional per-device upload rate cap in kbit/s; null = unlimited. */
    @ColumnInfo(name = "limit_up_kbps") val limitUpKbps: Int? = null,

    /** True while the device is administratively blocked from the network. */
    @ColumnInfo(name = "blocked", defaultValue = "0") val blocked: Boolean = false,

    /** The raw kernel counter reading at [lastSeen], for delta computation across restarts. */
    @ColumnInfo(name = "raw_tx", defaultValue = "0") val rawTx: Long = 0L,
    @ColumnInfo(name = "raw_rx", defaultValue = "0") val rawRx: Long = 0L,

    /**
     * Incremented whenever we detect the kernel counter went backwards, which means the accounting
     * rules were torn down and reinstalled (hotspot restarted, app reinstalled, device rebooted).
     * A changed epoch tells the collector that [rawTx]/[rawRx] must not be diffed against the new
     * reading.
     */
    @ColumnInfo(name = "counter_epoch", defaultValue = "0") val counterEpoch: Long = 0L,

    @ColumnInfo(name = "first_seen") val firstSeen: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_seen") val lastSeen: Long = System.currentTimeMillis(),
) {
    /** Label to show in the UI: nickname if set, else hostname, else a MAC-derived short name. */
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
            ?: "未知设备 ${mac.takeLast(5)}"
}

/**
 * Traffic accumulated by one device during one time bucket.
 *
 * One row per (device, bucket-start). Buckets are hourly, which keeps the table small enough for a
 * phone while still allowing day/week/month rollups by rounding the timestamp down.
 *
 * The primary key is a composite of MAC and bucket start, so repeated flushes upsert rather than
 * duplicate — this is what makes the collector's delta accounting crash-safe.
 */
@Entity(
    tableName = "usage_buckets",
    primaryKeys = ["mac", "bucket_start"],
    indices = [Index("bucket_start"), Index("mac")],
)
data class UsageBucketEntity(
    @ColumnInfo(name = "mac") val mac: String,
    @ColumnInfo(name = "bucket_start") val bucketStart: Long,
    @ColumnInfo(name = "tx_bytes", defaultValue = "0") val txBytes: Long = 0L,
    @ColumnInfo(name = "rx_bytes", defaultValue = "0") val rxBytes: Long = 0L,
) {
    val total: Long get() = txBytes + rxBytes
}

/** Aggregated usage for reporting; not a table. */
data class UsageAggregate(
    @ColumnInfo(name = "mac") val mac: String,
    @ColumnInfo(name = "tx_bytes") val txBytes: Long,
    @ColumnInfo(name = "rx_bytes") val rxBytes: Long,
) {
    val total: Long get() = txBytes + rxBytes
}

/** Per-device totals within a time range, joined with device metadata for display. */
data class DeviceUsageRow(
    val mac: String,
    val nickname: String?,
    val hostname: String?,
    val pricePerGb: Double,
    val billable: Boolean,
    val txBytes: Long,
    val rxBytes: Long,
) {
    val total: Long get() = txBytes + rxBytes
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
            ?: "未知设备 ${mac.takeLast(5)}"

    /** Gigabytes used, shown next to the charge. */
    val totalGb: Double get() = Billing.gbFor(total)

    /**
     * Charge owed for this range.
     *
     * Delegates to [Billing.chargeFor] so the history report and the live screen can never disagree
     * about how money is computed. Rounding happens at display time, so intermediate sums stay exact.
     */
    val charge: Double get() = Billing.chargeFor(total, pricePerGb, billable)
}
