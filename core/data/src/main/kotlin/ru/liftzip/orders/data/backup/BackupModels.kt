package ru.liftzip.orders.data.backup

import kotlinx.serialization.Serializable
import ru.liftzip.orders.data.database.AppMetadataEntity
import ru.liftzip.orders.data.database.ChangeEventEntity
import ru.liftzip.orders.data.database.CostEntryEntity
import ru.liftzip.orders.data.database.GroupCommandEntity
import ru.liftzip.orders.data.database.OrderCommandEntity
import ru.liftzip.orders.data.database.OrderEntity
import ru.liftzip.orders.data.database.OrderLineEntity
import ru.liftzip.orders.data.database.PriceRecordEntity
import ru.liftzip.orders.data.database.ProductEntity
import ru.liftzip.orders.data.database.SettlementGroupEntity

@Serializable
data class BackupPayload(
    val products: List<ProductEntity>,
    val groups: List<SettlementGroupEntity>,
    val orders: List<OrderEntity>,
    val lines: List<OrderLineEntity>,
    val costs: List<CostEntryEntity>,
    val priceRecords: List<PriceRecordEntity>,
    val changeEvents: List<ChangeEventEntity>,
    val orderCommands: List<OrderCommandEntity>,
    val groupCommands: List<GroupCommandEntity>,
    val metadata: List<AppMetadataEntity>,
    val deliveryInvoices: List<ru.liftzip.orders.data.database.DeliveryInvoiceEntity> = emptyList(),
    val deliveryPayments: List<ru.liftzip.orders.data.database.DeliveryPaymentEntity> = emptyList(),
)

@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val schemaVersion: Int,
    val createdAt: Long,
    val dataSha256: String,
    val counts: Map<String, Int>,
)

data class BackupInspection(
    val createdAt: Long,
    val schemaVersion: Int,
    val counts: Map<String, Int>,
) {
    val recordCount: Int = counts.values.sum()
}
