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
        AppMetadataEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class OrdersDatabase : RoomDatabase() {
    abstract fun ordersDao(): OrdersDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val SCHEMA_VERSION = 2
        const val DATABASE_NAME = "liftzip-orders.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(key))")
            }
        }

        fun create(context: Context): OrdersDatabase = Room.databaseBuilder(
            context.applicationContext,
            OrdersDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(MIGRATION_1_2)
            .addCallback(IntegrityCallback)
            .build()
    }
}

private object IntegrityCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) = createIntegrityIndex(db)
    override fun onOpen(db: SupportSQLiteDatabase) = createIntegrityIndex(db)

    private fun createIntegrityIndex(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_settlement_groups_one_forming " +
                "ON settlement_groups(state) WHERE state = 'FORMING'",
        )
    }
}
