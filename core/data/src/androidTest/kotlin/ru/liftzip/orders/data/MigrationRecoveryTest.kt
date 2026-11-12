package ru.liftzip.orders.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.data.database.OrdersDatabase
import java.io.File

@RunWith(AndroidJUnit4::class)
class MigrationRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrdersDatabase::class.java)
    private var database: OrdersDatabase? = null
    private val createdCopies = mutableListOf<File>()

    @After fun tearDown() {
        database?.close()
        context.deleteDatabase(OrdersDatabase.DATABASE_NAME)
        createdCopies.forEach { it.delete() }
    }

    @Test fun upgradingSchema4CreatesAnUntouchedRecoveryCopyBeforeMigrating() {
        helper.createDatabase(OrdersDatabase.DATABASE_NAME, 4).apply {
            execSQL("INSERT INTO settlement_groups VALUES('g',1,'CALCULATING',0,1,1,1,1)")
            execSQL("INSERT INTO products VALUES('p','Товар',NULL,NULL,NULL,'[]',1,1,1)")
            execSQL("INSERT INTO orders VALUES('o',1,'2026-11-11','Тест','g',500000,'original',0,'INCOMPLETE',1,1,1,120000)")
            execSQL("INSERT INTO order_lines VALUES('l','o','p','Товар',NULL,'2',250000,NULL,NULL,NULL,1,1,1,'2','25','13.25','2026-11-11')")
            execSQL("CREATE UNIQUE INDEX index_settlement_groups_one_forming ON settlement_groups(state) WHERE state='FORMING'")
            close()
        }
        val directory = File(context.filesDir, "db-migration-backups")
        val existing = directory.listFiles()?.toSet().orEmpty()
        val upgraded = OrdersDatabase.create(context).also { database = it }.openHelper.writableDatabase
        val copies = directory.listFiles()?.filter { it !in existing }.orEmpty()
        createdCopies += copies
        assertEquals(1, copies.size)
        SQLiteDatabase.openDatabase(copies.single().path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(4, old.version)
            old.rawQuery("SELECT receivedPaymentMinor,comment,manualDeliveryPriceMinor FROM orders", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(500000L, c.getLong(0))
                assertEquals("original", c.getString(1)); assertEquals(120000L, c.getLong(2))
            }
        }
        assertEquals(OrdersDatabase.SCHEMA_VERSION, upgraded.version)
        upgraded.query("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='index_settlement_groups_one_forming'").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        upgraded.query("SELECT orderRate,withdrawn,transferred,received FROM orders").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("13.25", c.getString(0))
            for (index in 1..3) assertEquals(0, c.getInt(index))
        }
    }
}
