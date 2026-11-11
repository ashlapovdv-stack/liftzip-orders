package ru.liftzip.orders.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BackupDao {
    @Query("SELECT * FROM products") suspend fun allProducts(): List<ProductEntity>
    @Query("SELECT * FROM settlement_groups") suspend fun allGroups(): List<SettlementGroupEntity>
    @Query("SELECT * FROM orders") suspend fun allOrders(): List<OrderEntity>
    @Query("SELECT * FROM order_lines") suspend fun allLines(): List<OrderLineEntity>
    @Query("SELECT * FROM cost_entries") suspend fun allCosts(): List<CostEntryEntity>
    @Query("SELECT * FROM price_records") suspend fun allPriceRecords(): List<PriceRecordEntity>
    @Query("SELECT * FROM change_events") suspend fun allChangeEvents(): List<ChangeEventEntity>
    @Query("SELECT * FROM order_commands") suspend fun allOrderCommands(): List<OrderCommandEntity>
    @Query("SELECT * FROM group_commands") suspend fun allGroupCommands(): List<GroupCommandEntity>
    @Query("SELECT * FROM app_metadata") suspend fun allMetadata(): List<AppMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertProducts(items: List<ProductEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGroups(items: List<SettlementGroupEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOrders(items: List<OrderEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLines(items: List<OrderLineEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCosts(items: List<CostEntryEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPriceRecords(items: List<PriceRecordEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertChangeEvents(items: List<ChangeEventEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOrderCommands(items: List<OrderCommandEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGroupCommands(items: List<GroupCommandEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMetadata(items: List<AppMetadataEntity>)

    @Query("DELETE FROM order_commands") suspend fun deleteOrderCommands()
    @Query("DELETE FROM group_commands") suspend fun deleteGroupCommands()
    @Query("DELETE FROM change_events") suspend fun deleteChangeEvents()
    @Query("DELETE FROM price_records") suspend fun deletePriceRecords()
    @Query("DELETE FROM cost_entries") suspend fun deleteCosts()
    @Query("DELETE FROM order_lines") suspend fun deleteLines()
    @Query("DELETE FROM orders") suspend fun deleteOrders()
    @Query("DELETE FROM products") suspend fun deleteProducts()
    @Query("DELETE FROM settlement_groups") suspend fun deleteGroups()
    @Query("DELETE FROM app_metadata") suspend fun deleteMetadata()
}
