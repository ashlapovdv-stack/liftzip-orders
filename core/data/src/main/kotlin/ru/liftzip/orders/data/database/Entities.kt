package ru.liftzip.orders.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "products", indices = [Index("normalizedArticle")])
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val article: String?,
    val normalizedArticle: String?,
    val brand: String?,
    val aliasesJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
)

@Serializable
@Entity(
    tableName = "settlement_groups",
    indices = [Index(value = ["number"], unique = true)],
)
data class SettlementGroupEntity(
    @PrimaryKey val id: String,
    val number: Long,
    val state: String,
    val costsConfirmed: Boolean,
    val createdAt: Long,
    val completedAt: Long?,
    val updatedAt: Long,
    val revision: Long,
)

@Serializable
@Entity(
    tableName = "orders",
    foreignKeys = [ForeignKey(
        entity = SettlementGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["number"], unique = true), Index("groupId")],
)
data class OrderEntity(
    @PrimaryKey val id: String,
    val number: Long,
    val date: String,
    val counterparty: String,
    val groupId: String,
    val receivedPaymentMinor: Long,
    val comment: String,
    val costsConfirmed: Boolean,
    val calculationState: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val manualDeliveryPriceMinor: Long? = null,
    val orderRate: String? = null,
    @ColumnInfo(defaultValue = "0") val withdrawn: Boolean = false,
    @ColumnInfo(defaultValue = "0") val transferred: Boolean = false,
    @ColumnInfo(defaultValue = "0") val received: Boolean = false,
)

@Serializable
@Entity(
    tableName = "order_lines",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("orderId"), Index("productId")],
)
data class OrderLineEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val productId: String,
    val productNameSnapshot: String,
    val articleSnapshot: String?,
    val quantity: String,
    val saleUnitPriceMinor: Long,
    val purchaseUnitPrice: String?,
    val purchaseCurrency: String?,
    val purchaseRate: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val deliveryWeight: String? = null,
    val deliveryTariff: String? = null,
    val deliveryRate: String? = null,
    val deliveryPlanDate: String? = null,
    val productUrl: String? = null,
    val actualWeight: String? = null,
)

@Serializable
@Entity(
    tableName = "cost_entries",
    indices = [Index(value = ["ownerType", "ownerId"])],
)
data class CostEntryEntity(
    @PrimaryKey val id: String,
    val ownerType: String,
    val ownerId: String,
    val kind: String,
    val amount: String,
    val currency: String,
    val rate: String,
    val isConfirmed: Boolean,
    val date: String,
    val comment: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
)

@Serializable
@Entity(
    tableName = "price_records",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OrderLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceLineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["productId", "eventAt"]),
        Index(value = ["sourceLineId", "type"], unique = true),
        Index("orderId"),
    ],
)
data class PriceRecordEntity(
    @PrimaryKey val id: String,
    val productId: String,
    val sourceLineId: String,
    val orderId: String,
    val type: String,
    val eventAt: Long,
    val price: String,
    val currency: String,
    val rate: String,
    val quantity: String,
    val preliminary: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
)

@Serializable
@Entity(
    tableName = "change_events",
    indices = [Index(value = ["entityId", "eventAt"])],
)
data class ChangeEventEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val changeType: String,
    val eventAt: Long,
    val localUser: String,
    val beforeJson: String?,
    val afterJson: String?,
)

@Serializable
@Entity(
    tableName = "order_commands",
    foreignKeys = [ForeignKey(
        entity = OrderEntity::class,
        parentColumns = ["id"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("orderId")],
)
data class OrderCommandEntity(
    @PrimaryKey val commandId: String,
    val orderId: String,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "group_commands",
    foreignKeys = [ForeignKey(
        entity = SettlementGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("groupId")],
)
data class GroupCommandEntity(
    @PrimaryKey val commandId: String,
    val groupId: String,
    val nextGroupId: String,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)
