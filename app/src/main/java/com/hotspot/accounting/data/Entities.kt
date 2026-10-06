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

    /** Last hostname reported via DHCP/DNS, useful before the user names it. */
    @ColumnInfo(name = "hostname") val hostname: String? = null,

    /** Last known IPv4 address. Advisory only; changes across leases. */
    @ColumnInfo(name = "last_ip") val lastIp: String? = null,

    /** Vendor resolved from the MAC OUI, when known. */
    @ColumnInfo(name = "vendor") val vendor: String? = null,

    /** Price in currency units per gigabyte. Zero means "not billed". */
    @ColumnInfo(name = "price_per_gb", defaultValue = "0") val pricePerGb: Double = 0.0,

    /** Free-form note, e.g. "包月" or "老客户". */
    @ColumnInfo(name = "billing_note") val billingNote: String? = null,

    /** Whether usage for this device should accrue charges at all. */
    @ColumnInfo(name = "billable", defaultValue = "1") val billable: Boolean = true,

    /**
     * Running total of money this device has been charged, in currency units.
     *
     * Accumulated incrementally as usage is persisted, rather than derived by re-summing every hour
     * bucket. That keeps the balance cheap to read, and more importantly makes it possible to
     * *write off* an amount without rewriting the underlying usage history.
     */
    @ColumnInfo(name = "charged_total", defaultValue = "0") val chargedTotal: Double = 0.0,

    /**
     * Debt deliberately carried over from an earlier session, awaiting an explicit decision.
     *
     * This exists because of a real hazard in the original design: kernel counters reset when the
     * hotspot restarts, and a returning device simply continued accumulating under the same MAC. Old
     * unsettled charges and new usage became indistinguishable in the total. When a device returns
     * after a long absence with an outstanding balance, [needsBillingReview] is set so the user is
     * asked whether to keep the old debt or settle it separately — instead of silently merging.
     */
    @ColumnInfo(name = "debt_carried", defaultValue = "0") val debtCarried: Double = 0.0,

    /** Set when a returning device has an unsettled balance that the user must acknowledge. */
    @ColumnInfo(name = "needs_billing_review", defaultValue = "0") val needsBillingReview: Boolean = false,

    /** When the device was last seen, used to decide whether a return counts as a new session. */
    @ColumnInfo(name = "offline_since") val offlineSince: Long? = null,

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
 * One money movement for a device: a payment received, or an adjustment/write-off.
 *
 * Modelled as an append-only ledger rather than a single "amount paid" field, because in practice
 * clients pay in instalments and sometimes partially. A running total could not answer "when did he
 * pay, and how much each time", which is exactly what is needed to chase an outstanding balance.
 *
 * [amount] is positive for money received and negative for a write-off (money forgiven), so the sum
 * can be subtracted from charges directly.
 */
@Entity(
    tableName = "payments",
    indices = [Index("mac"), Index("paid_at")],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,

    @ColumnInfo(name = "mac") val mac: String,

    /** Positive = received; negative = forgiven/written off. */
    @ColumnInfo(name = "amount") val amount: Double,

    /** When the money changed hands — user-supplied, because records may be entered later. */
    @ColumnInfo(name = "paid_at") val paidAt: Long,

    /** Free-form remark, e.g. "微信" or "先付一半". */
    @ColumnInfo(name = "note") val note: String? = null,

    /** When the record was created, which can differ from [paidAt]. */
    @ColumnInfo(name = "recorded_at") val recordedAt: Long = System.currentTimeMillis(),
)

/**
 * A device's money position: what it has been charged, what it has paid, and what remains.
 *
 * Computed once here so the live list, the report and the CSV can never disagree about a balance.
 */
data class DeviceLedger(
    val mac: String,
    /** Everything ever charged, including debt carried over from earlier sessions. */
    val charged: Double,
    /** Sum of payments received. */
    val paid: Double,
    /** Sum of write-offs (positive number). */
    val writtenOff: Double,
    /** Debt carried over from a previous session, included in [charged]. */
    val carriedDebt: Double,
    /** Charged minus paid minus written off. Positive means the client still owes money. */
    val outstanding: Double,
    /** Timestamp of the most recent payment, or null if none. */
    val lastPaymentAt: Long?,
) {
    /** True when nothing has been paid yet on a non-zero balance. */
    val untouched: Boolean get() = paid == 0.0 && writtenOff == 0.0 && charged > 0.0

    /** True when the client has overpaid and is in credit. */
    val inCredit: Boolean get() = outstanding < -0.005
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
