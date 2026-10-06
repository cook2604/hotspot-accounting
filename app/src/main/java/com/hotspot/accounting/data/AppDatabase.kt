package com.hotspot.accounting.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DeviceEntity::class, UsageBucketEntity::class, PaymentEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun usage(): UsageDao
    abstract fun payments(): PaymentDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hotspot-accounting.db",
                )
                    /**
                     * The destructive fallback is kept deliberately.
                     *
                     * Version 2 adds the payment ledger and the balance columns. Hand-writing a
                     * migration would be the right call for a shipped product, but this app is still
                     * pre-release and used by a single person: a subtly wrong migration would corrupt
                     * usage history silently, whereas a reset is loud and recoverable by simply
                     * letting the hotspot run again.
                     *
                     * **Upgrading from version 1 therefore clears existing usage data.**
                     */
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
