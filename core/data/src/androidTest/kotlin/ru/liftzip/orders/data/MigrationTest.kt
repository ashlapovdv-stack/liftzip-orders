package ru.liftzip.orders.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.data.database.OrdersDatabase

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val databaseName = "migration-orders-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrdersDatabase::class.java,
    )

    @Test fun migration1To2KeepsExistingGroup() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                "INSERT INTO settlement_groups " +
                    "(id, number, state, costsConfirmed, createdAt, completedAt, updatedAt, revision) " +
                    "VALUES ('00000000-0000-0000-0000-000000000001', 1, 'FORMING', 0, 1, NULL, 1, 1)",
            )
            close()
        }
        helper.runMigrationsAndValidate(databaseName, 2, true, OrdersDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT number, state FROM settlement_groups").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(1L, cursor.getLong(0))
                assertEquals("FORMING", cursor.getString(1))
            }
        }
    }
}
