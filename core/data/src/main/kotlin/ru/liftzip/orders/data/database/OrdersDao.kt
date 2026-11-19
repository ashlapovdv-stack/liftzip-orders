package ru.liftzip.orders.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OrdersDao {
    @Query("SELECT * FROM order_line_images WHERE lineId = :lineId ORDER BY position, id")
    suspend fun getLineImages(lineId: String): List<OrderLineImageEntity>

    @Query("SELECT * FROM order_line_images WHERE lineId IN (:lineIds) ORDER BY position, id")
    suspend fun getImagesForLines(lineIds: List<String>): List<OrderLineImageEntity>

    @Insert
    suspend fun insertLineImage(image: OrderLineImageEntity)

    @Query("DELETE FROM order_line_images WHERE lineId = :lineId")
    suspend fun deleteLineImages(lineId: String)

    @Query("""
        SELECT o.* FROM orders o
        WHERE (:filter = 'ALL'
            OR (:filter = 'NEEDS_CALCULATION' AND o.calculationState = 'INCOMPLETE')
            OR (:filter = 'IN_DELIVERY' AND o.withdrawn = 1 AND o.transferred = 1 AND o.received = 0))
        ORDER BY o.date DESC, o.number DESC LIMIT :limit
    """)
    fun observeOrders(filter: String, limit: Int): Flow<List<OrderEntity>>

    @Query("SELECT id, orderId, productNameSnapshot, articleSnapshot FROM order_lines")
    fun observeOrderSearchLines(): Flow<List<OrderSearchLine>>

    @Query("SELECT * FROM settlement_groups WHERE id IN (:ids)")
    suspend fun getGroupsByIds(ids: List<String>): List<SettlementGroupEntity>

    @Query("SELECT * FROM order_lines WHERE orderId IN (:ids) ORDER BY createdAt, id")
    suspend fun getLinesForOrders(ids: List<String>): List<OrderLineEntity>

    @Query("SELECT * FROM cost_entries WHERE ownerType = :owner AND ownerId IN (:ids)")
    suspend fun getCostsForOwners(owner: String, ids: List<String>): List<CostEntryEntity>

    @Query("UPDATE orders SET calculationState = 'INCOMPLETE' WHERE calculationState != 'INCOMPLETE' AND NOT EXISTS (SELECT 1 FROM order_lines WHERE orderId = orders.id)")
    suspend fun markEmptyOrdersIncomplete()

    @Query("SELECT * FROM orders WHERE id = :id")
    fun observeOrder(id: String): Flow<OrderEntity?>

    @Query("SELECT * FROM settlement_groups ORDER BY number DESC")
    fun observeGroups(): Flow<List<SettlementGroupEntity>>
    @Query("SELECT * FROM settlement_groups ORDER BY number DESC")
    suspend fun getGroups(): List<SettlementGroupEntity>

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

    @Query("SELECT * FROM products ORDER BY updatedAt DESC, name COLLATE NOCASE, id LIMIT :limit")
    fun observeProducts(limit: Int): Flow<List<ProductEntity>>

    @Query("SELECT * FROM price_records WHERE productId = :productId ORDER BY eventAt DESC, updatedAt DESC")
    fun observePriceRecords(productId: String): Flow<List<PriceRecordEntity>>

    @Query("SELECT COUNT(*) FROM order_lines l JOIN orders o ON l.orderId = o.id WHERE o.groupId = :groupId")
    fun observeGroupLineCount(groupId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM cost_entries WHERE (ownerType = 'GROUP' AND ownerId = :groupId) OR ownerId IN (SELECT id FROM orders WHERE groupId = :groupId) OR ownerId IN (SELECT l.id FROM order_lines l JOIN orders o ON l.orderId = o.id WHERE o.groupId = :groupId)")
    fun observeGroupCostCount(groupId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM order_line_images i JOIN order_lines l ON i.lineId = l.id WHERE l.productId = :productId")
    fun observeProductImageCount(productId: String): Flow<Int>

    @Query("SELECT * FROM order_lines WHERE productId = :productId")
    fun observeProductLines(productId: String): Flow<List<OrderLineEntity>>

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

    @Query("SELECT * FROM products WHERE normalizedArticle IS :normalizedArticle ORDER BY createdAt, id")
    suspend fun reusableProducts(normalizedArticle: String?): List<ProductEntity>

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

data class OrderSearchLine(val id: String, val orderId: String, val productNameSnapshot: String, val articleSnapshot: String?)
