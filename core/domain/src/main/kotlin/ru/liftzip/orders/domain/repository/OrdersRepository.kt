package ru.liftzip.orders.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.liftzip.orders.domain.model.FinishGroupCommand
import ru.liftzip.orders.domain.model.FinishGroupResult
import ru.liftzip.orders.domain.model.GroupDetails
import ru.liftzip.orders.domain.model.GroupSummary
import ru.liftzip.orders.domain.model.OrderDetails
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.model.OrdersFilter
import ru.liftzip.orders.domain.model.Product
import ru.liftzip.orders.domain.model.ProductPriceHistory
import ru.liftzip.orders.domain.model.SaveGroupCostsCommand
import ru.liftzip.orders.domain.model.SaveOrderCommand
import java.util.UUID

interface OrdersRepository {
    fun observeDelivery(orderId: UUID): Flow<ru.liftzip.orders.domain.delivery.DeliveryDetails?>
    suspend fun saveDeliveryInvoice(invoice: ru.liftzip.orders.domain.delivery.DeliveryInvoice)
    suspend fun addDeliveryPayment(payment: ru.liftzip.orders.domain.delivery.DeliveryPayment)
    suspend fun reverseDeliveryPayment(id: UUID, commandId: UUID)

    fun observeOrders(query: String, filter: OrdersFilter, limit: Int): Flow<List<OrderSummary>>
    fun observeOrder(orderId: UUID): Flow<OrderDetails?>
    fun observeGroups(): Flow<List<GroupSummary>>
    fun observeGroup(groupId: UUID): Flow<GroupDetails?>
    fun observeProducts(query: String, limit: Int = 100): Flow<List<Product>>
    fun observeProductHistory(productId: UUID): Flow<ProductPriceHistory?>
    suspend fun deleteOrder(orderId: UUID)
    suspend fun saveOrder(command: SaveOrderCommand): UUID
    suspend fun setOrderAction(orderId: UUID, action: ru.liftzip.orders.domain.model.OrderAction, checked: Boolean)
    suspend fun saveGroupCosts(command: SaveGroupCostsCommand)
    suspend fun finishGroup(command: FinishGroupCommand): FinishGroupResult
}
