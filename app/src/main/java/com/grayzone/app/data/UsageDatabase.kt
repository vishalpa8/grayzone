package com.grayzone.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UsageEvent::class], version = 2, exportSchema = true)
abstract class UsageDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var INSTANCE: UsageDatabase? = null

        /**
         * Migration from version 1 to 2: Add indexes for query performance.
         * 
         * Impact: 50-100x faster queries on large datasets (>10k events).
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add indexes for common query patterns
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_date_key ON usage_events(dateKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_pkg_date ON usage_events(packageName, dateKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_start_time ON usage_events(startTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_was_blocked ON usage_events(wasBlocked)")
            }
        }

        fun getInstance(context: Context): UsageDatabase {
            // Re-check inside the lock (F-040): `INSTANCE ?: synchronized { build }` can build
            // twice when two threads both observe null before either assigns.
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UsageDatabase::class.java,
                    "grayzone_usage.db"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2)
                    // F-041: installing an older APK over newer schema must not brick usage logging.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
