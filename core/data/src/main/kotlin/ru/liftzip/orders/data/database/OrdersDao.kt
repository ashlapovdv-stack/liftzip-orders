package ru.liftzip.orders.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OrdersDao {
    @Query("""
        SELECT o.* FROM orders o
        WHERE (:filter = 'ALL' OR o.calculationState = 'INCOMPLETE')
          AND (:query = '' OR CAST(o.number AS TEXT) LIKE :query
            OR o.counterparty LIKE :query
            OR EXISTS (
                SELECT 1 FROM order_lines ol
                WHERE ol.orderId = o.id AND (ol.productNameSnapshot LIKE :query OR ol.articleSnapshot LIKE :query)
            ))
        ORDER BY o.date DESC, o.number DESC LIMIT :limit
    """)
    fun observeOrders(query: String, filter: String, limit: Int): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE id = :id")
    fun observeOrder(id: String): Flow<OrderEntity?>

    @Query("SELECT * FROM settlement_groups ORDER BY number DESC")
    fun observeGroups(): Flow<List<SettlementGroupEntity>>

    @Query("SELECT * FROM orders")
    fun observeAllOrders(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM order_lines")
    fun observeAllLines(): Flow<List<OrderLineEntity>>

    @Query("SELECT * FROM cost_entries")
    fun observeAllCosts(): Flow<List<CostEntryEntity>>

    @Query("SELECT * FROM settlement_groups WHERE id = :id")
    fun observeGroup(id: String): Flow<SettlementGroupEntity?>

    @Query("SELECT * FROM orders WHERE groupId = :groupId ORDER BY number DESC")
    fun observeGroupOrders(groupId: String): Flow<List<OrderEntity>>

    @Query("""
        SELECT DISTINCT p.* FROM products p
        WHERE (:query = '' OR p.name LIKE :query OR p.article LIKE :query
            OR p.aliasesJson LIKE :query OR p.brand LIKE :query)
        ORDER BY p.updatedAt DESC, p.name COLLATE NOCASE LIMIT :limit
    """)
    fun observeProducts(query: String, limit: Int): Flow<List<ProductEntity>>

    @Query("SELECT * FROM price_records WHERE productId = :productId ORDER BY eventAt DESC, updatedAt DESC")
    fun observePriceRecords(productId: String): Flow<List<PriceRecordEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    fun observeProduct(id: String): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProduct(id: String): ProductEntity?

    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun getOrder(id: String): OrderEntity?

    @Query("SELECT * FROM settlement_groups WHERE id = :id")
    suspend fun getGroup(id: String): SettlementGroupEntity?

    @Query("SELECT * FROM settlement_groups WHERE state = 'FORMING' LIMIT 1")
    suspend fun getFormingGroup(): SettlementGroupEntity?

    @Query("SELECT * FROM order_lines WHERE orderId = :orderId ORDER BY createdAt, id")
    suspend fun getOrderLines(orderId: String): List<OrderLineEntity>

    @Query("SELECT * FROM cost_entries WHERE (ownerType = 'ORDER' AND ownerId = :orderId) OR (ownerType = 'LINE' AND ownerId IN (SELECT id FROM order_lines WHERE orderId = :orderId))")
    suspend fun getOrderCosts(orderId: String): List<CostEntryEntity>

    @Query("SELECT * FROM cost_entries WHERE ownerType = 'GROUP' AND ownerId = :groupId")
    suspend fun getGroupCosts(groupId: String): List<CostEntryEntity>

    @Query("SELECT * FROM change_events WHERE entityType = 'ORDER' AND entityId = :orderId ORDER BY eventAt DESC")
    suspend fun getOrderHistory(orderId: String): List<ChangeEventEntity>

    @Query("SELECT * FROM orders WHERE groupId = :groupId ORDER BY number")
    suspend fun getGroupOrders(groupId: String): List<OrderEntity>

    @Query("SELECT * FROM orders WHERE id IN (:ids)")
    suspend fun getOrdersByIds(ids: List<String>): List<OrderEntity>

    @Query("SELECT * FROM price_records WHERE sourceLineId = :lineId AND type = :type")
    suspend fun getPriceRecord(lineId: String, type: String): PriceRecordEntity?

    @Query("SELECT COUNT(*) FROM orders WHERE groupId = :groupId")
    suspend fun countOrdersInGroup(groupId: String): Int

    @Query("SELECT MAX(COALESCE((SELECT MAX(number) FROM orders), 0), CAST(COALESCE((SELECT value FROM app_metadata WHERE `key` = 'order_number_high_water'), '0') AS INTEGER)) + 1")
    suspend fun nextOrderNumber(): Long

    @Query("SELECT COALESCE(MAX(number), 0) + 1 FROM settlement_groups")
    suspend fun nextGroupNumber(): Long

    @Query("""
        SELECT * FROM products WHERE lower(trim(name)) = lower(trim(:name))
        AND ((:normalizedArticle IS NULL AND normalizedArticle IS NULL) OR normalizedArticle = :normalizedArticle)
        LIMIT 1
    """)
    suspend fun findReusableProduct(name: String, normalizedArticle: String?): ProductEntity?

    @Query("SELECT * FROM order_commands WHERE commandId = :commandId")
    suspend fun getOrderCommand(commandId: String): OrderCommandEntity?

    @Query("SELECT * FROM group_commands WHERE commandId = :commandId")
    suspend fun getGroupCommand(commandId: String): GroupCommandEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: SettlementGroupEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrder(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLine(line: OrderLineEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProduct(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCost(cost: CostEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPriceRecord(record: PriceRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChangeEvent(event: ChangeEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrderCommand(command: OrderCommandEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroupCommand(command: GroupCommandEntity)

    @Update
    suspend fun updateOrder(order: OrderEntity)

    @Update
    suspend fun updateGroup(group: SettlementGroupEntity)

    @Update
    suspend fun updateLine(line: OrderLineEntity)

    @Update
    suspend fun updatePriceRecord(record: PriceRecordEntity)

    @Query("DELETE FROM orders WHERE id = :id")
    suspend fun deleteOrder(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(item: AppMetadataEntity)

    @Query("DELETE FROM order_lines WHERE orderId = :orderId AND id NOT IN (:ids)")
    suspend fun deleteRemovedLines(orderId: String, ids: List<String>)

    @Query("DELETE FROM cost_entries WHERE (ownerType = 'ORDER' AND ownerId = :orderId) OR (ownerType = 'LINE' AND ownerId IN (SELECT id FROM order_lines WHERE orderId = :orderId))")
    suspend fun deleteOrderCosts(orderId: String)

    @Query("DELETE FROM cost_entries WHERE ownerType = 'GROUP' AND ownerId = :groupId")
    suspend fun deleteGroupCosts(groupId: String)

    @Query("DELETE FROM price_records WHERE sourceLineId = :lineId AND type = :type")
    suspend fun deletePriceRecord(lineId: String, type: String)
}
