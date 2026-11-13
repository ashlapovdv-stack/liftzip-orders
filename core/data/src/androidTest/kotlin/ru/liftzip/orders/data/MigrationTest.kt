package ru.liftzip.orders.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.After
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

    @After fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

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
    @Test fun migration2To3PreservesOrderAndAddsEmptyLogistics() {
        helper.createDatabase(databaseName,2).apply {
            execSQL("INSERT INTO settlement_groups VALUES('g',1,'FORMING',0,1,NULL,1,1)")
            execSQL("INSERT INTO products VALUES('p','Товар',NULL,NULL,NULL,'[]',1,1,1)")
            execSQL("INSERT INTO orders VALUES('o',1,'2026-11-11','Денис','g',500000,'old',0,'INCOMPLETE',1,1,1)")
            execSQL("INSERT INTO order_lines VALUES('l','o','p','Товар',NULL,'2',250000,'100','CNY','13.25',1,1,1)")
            execSQL("CREATE UNIQUE INDEX index_settlement_groups_one_forming ON settlement_groups(state) WHERE state='FORMING'")
            close()
        }
        helper.runMigrationsAndValidate(databaseName,3,true,OrdersDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT receivedPaymentMinor FROM orders WHERE id='o'").use { c -> c.moveToFirst();assertEquals(500000L,c.getLong(0)) }
            db.query("SELECT deliveryWeight FROM order_lines WHERE id='l'").use { c -> c.moveToFirst();assertEquals(true,c.isNull(0)) }
            db.query("SELECT count(*) FROM delivery_payments").use { c -> c.moveToFirst();assertEquals(0,c.getInt(0)) }
        }
    }

    @Test fun migrations3Through6PreserveUnknownFieldsAndInitializeActions() {
        helper.createDatabase(databaseName, 3).apply {
            execSQL("INSERT INTO settlement_groups VALUES('g',1,'CALCULATING',0,1,1,1,1)")
            execSQL("INSERT INTO products VALUES('p','Товар',NULL,NULL,NULL,'[]',1,1,1)")
            execSQL("INSERT INTO orders VALUES('o',1,'2026-11-11','Тест','g',500000,'old',0,'INCOMPLETE',1,1,1)")
            execSQL("INSERT INTO order_lines VALUES('l','o','p','Товар',NULL,'2',250000,NULL,NULL,NULL,1,1,1,NULL,NULL,NULL,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(databaseName, OrdersDatabase.SCHEMA_VERSION, true,
            OrdersDatabase.MIGRATION_3_4, OrdersDatabase.MIGRATION_4_5, OrdersDatabase.MIGRATION_5_6, OrdersDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT receivedPaymentMinor,manualDeliveryPriceMinor,orderRate,withdrawn,transferred,received FROM orders").use { c ->
                c.moveToFirst(); assertEquals(500000L, c.getLong(0))
                assertEquals(true, c.isNull(1)); assertEquals(true, c.isNull(2))
                for (index in 3..5) assertEquals(0, c.getInt(index))
            }
        }
    }

    @Test fun migration5To6KeepsRateAndDoesNotInventCompletedActions() {
        helper.createDatabase(databaseName, 5).apply {
            execSQL("INSERT INTO settlement_groups VALUES('g',1,'CALCULATING',0,1,1,1,1)")
            execSQL("INSERT INTO orders VALUES('o',1,'2026-11-11','Тест','g',0,'old',0,'INCOMPLETE',1,1,1,0,'13.25')")
            execSQL("CREATE UNIQUE INDEX index_settlement_groups_one_forming ON settlement_groups(state) WHERE state='FORMING'")
            close()
        }
        helper.runMigrationsAndValidate(databaseName, OrdersDatabase.SCHEMA_VERSION, true, OrdersDatabase.MIGRATION_5_6, OrdersDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT orderRate,manualDeliveryPriceMinor,withdrawn,transferred,received FROM orders").use { c ->
                c.moveToFirst(); assertEquals("13.25", c.getString(0)); assertEquals(0L, c.getLong(1))
                for (index in 2..4) assertEquals(0, c.getInt(index))
            }
        }
    }

}
