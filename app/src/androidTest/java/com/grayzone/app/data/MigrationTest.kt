package com.grayzone.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        "com.grayzone.app.data.UsageDatabase",
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        // Create the database in version 1
        var db = helper.createDatabase(TEST_DB, 1)

        // Insert some data into version 1 (which doesn't have the indices yet)
        db.execSQL("INSERT INTO usage_events (packageName, appName, startTime, endTime, durationMillis, wasBlocked, dateKey) VALUES ('com.test.app', 'Test App', 1000, 2000, 1000, 0, '2023-10-01')")

        // Prepare for the next version
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, UsageDatabase.MIGRATION_1_2)

        // Ensure the indices were created by querying sqlite_master
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='usage_events'")
        val indices = mutableListOf<String>()
        while (cursor.moveToNext()) {
            indices.add(cursor.getString(0))
        }
        cursor.close()

        assert(indices.contains("idx_date_key"))
        assert(indices.contains("idx_pkg_date"))
        assert(indices.contains("idx_start_time"))
        assert(indices.contains("idx_was_blocked"))
    }
}
