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
        const val SCHEMA_VERSION = 9
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

        fun create(context: Context): OrdersDatabase {
            preserveBeforeMigration(context)
            return Room.databaseBuilder(
            context.applicationContext,
            OrdersDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .addCallback(IntegrityCallback)
            .build()
        }

        private fun preserveBeforeMigration(context: Context) {
            val source = context.getDatabasePath(DATABASE_NAME)
            if (!source.exists()) return
            val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(source.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            val oldVersion = sqlite.version
            try {
                if (oldVersion >= SCHEMA_VERSION) return
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0) { "База занята: безопасное обновление отложено" }
                }
            } finally { sqlite.close() }
            val folder = java.io.File(context.filesDir, "db-migration-backups").apply { check(mkdirs() || isDirectory) }
            source.copyTo(java.io.File(folder, "before-$oldVersion-to-$SCHEMA_VERSION-${System.currentTimeMillis()}.db"))
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
