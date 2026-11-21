package ru.liftzip.orders.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProductEntity::class,
        OrderLineImageEntity::class,
        SettlementGroupEntity::class,
        OrderEntity::class,
        OrderLineEntity::class,
        CostEntryEntity::class,
        PriceRecordEntity::class,
        ChangeEventEntity::class,
        OrderCommandEntity::class,
        GroupCommandEntity::class,
        AppMetadataEntity::class, DeliveryInvoiceEntity::class, DeliveryPaymentEntity::class,
    ],
    version = OrdersDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class OrdersDatabase : RoomDatabase() {
    abstract fun deliveryDao(): DeliveryDao
    abstract fun ordersDao(): OrdersDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val SCHEMA_VERSION = 13
        const val DATABASE_NAME = "liftzip-orders.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(key))")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room validates declared indices; recreate our partial integrity index in onOpen.
                db.execSQL("DROP INDEX IF EXISTS index_settlement_groups_one_forming")
                listOf("deliveryWeight", "deliveryTariff", "deliveryRate", "deliveryPlanDate").forEach {
                    db.execSQL("ALTER TABLE order_lines ADD COLUMN `$it` TEXT")
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS delivery_invoices (orderId TEXT NOT NULL, amountMinor INTEGER, date TEXT, actualWeight TEXT, actualTariff TEXT, actualRate TEXT, note TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(orderId), FOREIGN KEY(orderId) REFERENCES orders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS delivery_payments (id TEXT NOT NULL, orderId TEXT NOT NULL, party TEXT NOT NULL, amountMinor INTEGER NOT NULL, date TEXT NOT NULL, note TEXT NOT NULL, reversalOf TEXT, PRIMARY KEY(id), FOREIGN KEY(orderId) REFERENCES orders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_delivery_payments_orderId ON delivery_payments(orderId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_delivery_payments_reversalOf ON delivery_payments(reversalOf)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE orders ADD COLUMN orderRate TEXT")
                db.execSQL("UPDATE orders SET orderRate = (SELECT deliveryRate FROM order_lines WHERE order_lines.orderId = orders.id AND deliveryRate IS NOT NULL LIMIT 1)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE orders ADD COLUMN manualDeliveryPriceMinor INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // This integrity index belongs to onOpen, outside Room's declared schema.
                db.execSQL("DROP INDEX IF EXISTS index_settlement_groups_one_forming")
                listOf("withdrawn", "transferred", "received").forEach {
                    db.execSQL("ALTER TABLE orders ADD COLUMN `$it` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE order_lines ADD COLUMN productUrl TEXT")
            }
        }

        // Room cannot model partial indices. Enforce the one-FORMING-group invariant
        // with SQLite triggers, which Room does not treat as schema indices.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_settlement_groups_one_forming")
                createFormationGuards(db)
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE order_lines ADD COLUMN actualWeight TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS order_line_images (id TEXT NOT NULL PRIMARY KEY, lineId TEXT NOT NULL, position INTEGER NOT NULL, jpeg BLOB NOT NULL, FOREIGN KEY(lineId) REFERENCES order_lines(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_order_line_images_lineId ON order_line_images(lineId)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE order_lines ADD COLUMN temporaryProductUrl TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room may migrate with foreign keys disabled, leaving dependent rows in place.\n                // Preserve them if cascading deletes occur, without duplicating surviving rows.
                db.execSQL("CREATE TEMP TABLE saved_line_images AS SELECT * FROM order_line_images")
                db.execSQL("CREATE TEMP TABLE saved_price_records AS SELECT * FROM price_records")
                db.execSQL("CREATE TABLE order_lines_new (id TEXT NOT NULL PRIMARY KEY, orderId TEXT NOT NULL, productId TEXT NOT NULL, productNameSnapshot TEXT NOT NULL, articleSnapshot TEXT, quantity TEXT, saleUnitPriceMinor INTEGER, purchaseUnitPrice TEXT, purchaseCurrency TEXT, purchaseRate TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, deliveryWeight TEXT, deliveryTariff TEXT, deliveryRate TEXT, deliveryPlanDate TEXT, productUrl TEXT, actualWeight TEXT, temporaryProductUrl TEXT, FOREIGN KEY(orderId) REFERENCES orders(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(productId) REFERENCES products(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("INSERT INTO order_lines_new SELECT * FROM order_lines")
                db.execSQL("DROP TABLE order_lines")
                db.execSQL("ALTER TABLE order_lines_new RENAME TO order_lines")
                db.execSQL("CREATE INDEX index_order_lines_orderId ON order_lines(orderId)")
                db.execSQL("CREATE INDEX index_order_lines_productId ON order_lines(productId)")
                db.execSQL("INSERT OR IGNORE INTO order_line_images SELECT * FROM saved_line_images")
                db.execSQL("INSERT OR IGNORE INTO price_records SELECT * FROM saved_price_records")
                db.execSQL("DROP TABLE saved_line_images")
                db.execSQL("DROP TABLE saved_price_records")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE order_lines ADD COLUMN comment TEXT")
            }
        }

        fun create(context: Context, name: String = DATABASE_NAME): OrdersDatabase {
            preserveBeforeMigration(context, name)
            return Room.databaseBuilder(
            context.applicationContext,
            OrdersDatabase::class.java,
            name,
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .addCallback(IntegrityCallback)
            .build()
        }

        private fun preserveBeforeMigration(context: Context, name: String) {
            val source = context.getDatabasePath(name)
            if (!source.exists()) return
            val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(source.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            val oldVersion = try {
                sqlite.version
            } catch (error: Exception) {
                sqlite.close()
                throw error
            }
            try {
                if (oldVersion >= SCHEMA_VERSION) return
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0) { "База занята: безопасное обновление отложено" }
                }
            } finally { sqlite.close() }
            val folder = java.io.File(context.filesDir, "db-migration-backups").apply { check(mkdirs() || isDirectory) }
            val copy = java.io.File(folder, "before-$oldVersion-to-$SCHEMA_VERSION-${System.currentTimeMillis()}.db")
            val pending = java.io.File(folder, "${copy.name}.tmp")
            try {
                source.copyTo(pending)
                check(pending.length() == source.length() && pending.renameTo(copy)) { "Не удалось сохранить копию базы" }
            } finally {
                pending.delete()
            }
        }
    }
}

private fun createFormationGuards(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS settlement_groups_one_forming_insert
           BEFORE INSERT ON settlement_groups
           WHEN NEW.state = 'FORMING' AND EXISTS (
               SELECT 1 FROM settlement_groups WHERE state = 'FORMING'
           )
           BEGIN SELECT RAISE(ABORT, 'Only one forming group is allowed'); END"""
    )
    db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS settlement_groups_one_forming_update
           BEFORE UPDATE OF state ON settlement_groups
           WHEN NEW.state = 'FORMING' AND EXISTS (
               SELECT 1 FROM settlement_groups WHERE state = 'FORMING' AND id != OLD.id
           )
           BEGIN SELECT RAISE(ABORT, 'Only one forming group is allowed'); END"""
    )
}

private object IntegrityCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) = createFormationGuards(db)
    override fun onOpen(db: SupportSQLiteDatabase) = createFormationGuards(db)
}
