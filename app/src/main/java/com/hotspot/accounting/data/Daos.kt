package com.hotspot.accounting.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    @Query("SELECT * FROM devices ORDER BY last_seen DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices ORDER BY last_seen DESC")
    suspend fun getAll(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    suspend fun get(mac: String): DeviceEntity?

    @Query("SELECT mac FROM devices")
    suspend fun allMacs(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(device: DeviceEntity): Long

    @Update
    suspend fun update(device: DeviceEntity)

    @Query(
        """
        UPDATE devices SET
            nickname = :nickname,
            price_per_gb = :pricePerGb,
            billing_note = :note,
            billable = :billable,
            limit_down_kbps = :limitDownKbps,
            limit_up_kbps = :limitUpKbps
        WHERE mac = :mac
        """
    )
    suspend fun updateBillingProfile(
        mac: String,
        nickname: String?,
        pricePerGb: Double,
        note: String?,
        billable: Boolean,
        limitDownKbps: Int?,
        limitUpKbps: Int?,
    )

    @Query("UPDATE devices SET blocked = :blocked WHERE mac = :mac")
    suspend fun setBlocked(mac: String, blocked: Boolean)

    @Query("UPDATE devices SET last_ip = :ip, hostname = COALESCE(:hostname, hostname), last_seen = :seenAt WHERE mac = :mac")
    suspend fun touch(mac: String, ip: String?, hostname: String?, seenAt: Long)

    /**
     * Applies the app-wide default billing rate to a device.
     *
     * Only used at discovery time, so a device the user has already priced by hand is never
     * overwritten (a newly inserted row has price 0, and this runs immediately after insert).
     */
    @Query("UPDATE devices SET price_per_gb = :pricePerGb, billable = :billable WHERE mac = :mac")
    suspend fun applyDefaultPrice(mac: String, pricePerGb: Double, billable: Boolean)

    /**
     * Applies the default rate to every device that has no price yet.
     *
     * Deliberately restricted to `price_per_gb = 0`, so a rate the user set by hand — including a
     * deliberate zero meaning "this device is free" — is never overwritten. Returns the number of
     * rows changed so the UI can report what actually happened.
     */
    @Query(
        """
        UPDATE devices
        SET price_per_gb = :pricePerGb, billable = :billable
        WHERE price_per_gb = 0
        """
    )
    suspend fun applyDefaultPriceToUnpriced(pricePerGb: Double, billable: Boolean): Int

    /** Persists the raw kernel counter baseline plus the epoch it belongs to. */
    @Query("UPDATE devices SET raw_tx = :rawTx, raw_rx = :rawRx, counter_epoch = :epoch WHERE mac = :mac")
    suspend fun saveCounterBaseline(mac: String, rawTx: Long, rawRx: Long, epoch: Long)

    @Query("DELETE FROM devices WHERE mac = :mac")
    suspend fun delete(mac: String)
}

@Dao
interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(bucket: UsageBucketEntity): Long

    /** Accumulates bytes into a bucket; creates the row first via [insertIfAbsent]. */
    @Query(
        """
        UPDATE usage_buckets
        SET tx_bytes = tx_bytes + :tx, rx_bytes = rx_bytes + :rx
        WHERE mac = :mac AND bucket_start = :bucketStart
        """
    )
    suspend fun addBytes(mac: String, bucketStart: Long, tx: Long, rx: Long): Int

    /**
     * Applies a delta to a bucket, creating it when absent.
     *
     * Wrapped in a transaction so a crash between insert and update cannot lose or duplicate the
     * delta: the caller only clears its in-memory pending map after this returns.
     */
    @Transaction
    suspend fun accumulate(mac: String, bucketStart: Long, tx: Long, rx: Long) {
        insertIfAbsent(UsageBucketEntity(mac = mac, bucketStart = bucketStart))
        addBytes(mac, bucketStart, tx, rx)
    }

    @Query(
        """
        SELECT mac, SUM(tx_bytes) AS tx_bytes, SUM(rx_bytes) AS rx_bytes
        FROM usage_buckets
        WHERE bucket_start >= :from AND bucket_start < :to
        GROUP BY mac
        """
    )
    suspend fun aggregateByMac(from: Long, to: Long): List<UsageAggregate>

    @Query(
        """
        SELECT d.mac AS mac, d.nickname AS nickname, d.hostname AS hostname,
               d.price_per_gb AS pricePerGb, d.billable AS billable,
               COALESCE(SUM(u.tx_bytes), 0) AS txBytes,
               COALESCE(SUM(u.rx_bytes), 0) AS rxBytes
        FROM devices d
        LEFT JOIN usage_buckets u
          ON u.mac = d.mac AND u.bucket_start >= :from AND u.bucket_start < :to
        GROUP BY d.mac
        ORDER BY (COALESCE(SUM(u.tx_bytes), 0) + COALESCE(SUM(u.rx_bytes), 0)) DESC
        """
    )
    suspend fun usageRows(from: Long, to: Long): List<DeviceUsageRow>

    @Query(
        """
        SELECT (COALESCE(SUM(u.tx_bytes), 0) + COALESCE(SUM(u.rx_bytes), 0))
        FROM usage_buckets u
        WHERE u.bucket_start >= :from AND u.bucket_start < :to
        """
    )
    suspend fun totalBytes(from: Long, to: Long): Long

    @Query(
        """
        SELECT mac AS mac, SUM(tx_bytes) AS tx_bytes, SUM(rx_bytes) AS rx_bytes
        FROM usage_buckets
        WHERE bucket_start >= :from AND bucket_start < :to
        GROUP BY mac
        """
    )
    fun observeAggregateByMac(from: Long, to: Long): Flow<List<UsageAggregate>>

    /** Daily totals for charting. */
    @Query(
        """
        SELECT (bucket_start / 86400000) AS dayIndex,
               SUM(tx_bytes) AS txBytes,
               SUM(rx_bytes) AS rxBytes
        FROM usage_buckets
        WHERE bucket_start >= :from AND bucket_start < :to
        GROUP BY dayIndex
        ORDER BY dayIndex
        """
    )
    suspend fun dailyTotals(from: Long, to: Long): List<DailyTotal>

    @Query("SELECT * FROM usage_buckets WHERE bucket_start >= :from AND bucket_start < :to ORDER BY bucket_start")
    suspend fun rawRange(from: Long, to: Long): List<UsageBucketEntity>

    @Query("DELETE FROM usage_buckets WHERE bucket_start < :before")
    suspend fun purgeBefore(before: Long): Int

    @Query("DELETE FROM usage_buckets WHERE mac = :mac")
    suspend fun deleteForDevice(mac: String)
}

/** One day's aggregate, keyed by epoch-day index. */
data class DailyTotal(
    val dayIndex: Long,
    val txBytes: Long,
    val rxBytes: Long,
) {
    val total: Long get() = txBytes + rxBytes
}
