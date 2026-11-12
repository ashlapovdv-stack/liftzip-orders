package ru.liftzip.orders.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class Currency { RUB, CNY }
enum class GroupState { FORMING, CALCULATING, COMPLETED }
enum class OrderAction { WITHDRAWN, TRANSFERRED, RECEIVED }
enum class OrderStage { ACCEPTED, AWAITING_PAYMENT, IN_DELIVERY, COMPLETED }

data class OrderFulfillment(
    val withdrawn: Boolean = false,
    val transferred: Boolean = false,
    val received: Boolean = false,
) {
    val isComplete: Boolean get() = withdrawn && transferred && received

    // Flags remain independent; the earliest unfinished action determines the displayed stage.
    val stage: OrderStage get() = when {
        !withdrawn -> OrderStage.ACCEPTED
        !transferred -> OrderStage.AWAITING_PAYMENT
        !received -> OrderStage.IN_DELIVERY
        else -> OrderStage.COMPLETED
    }

    fun withAction(action: OrderAction, checked: Boolean): OrderFulfillment = when (action) {
        OrderAction.WITHDRAWN -> copy(withdrawn = checked)
        OrderAction.TRANSFERRED -> copy(transferred = checked)
        OrderAction.RECEIVED -> copy(received = checked)
    }
}
enum class CalculationState { INCOMPLETE, PRELIMINARY }
enum class CostOwnerType { LINE, ORDER, GROUP }
enum class CostKind { PURCHASE, DELIVERY, COMMISSION, OTHER }
enum class PriceEventType { SALE, PURCHASE }
enum class OrdersFilter { ALL, NEEDS_CALCULATION }

@JvmInline
value class Money(val minorUnits: Long) : Comparable<Money> {
    init {
        // Long уже хранит значение в минимальных единицах; переполнение проверяется при создании.
    }

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(minorUnits, 2)
    operator fun plus(other: Money): Money = Money(Math.addExact(minorUnits, other.minorUnits))
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minorUnits, other.minorUnits))
    override fun compareTo(other: Money): Int = minorUnits.compareTo(other.minorUnits)

    companion object {
        val ZERO = Money(0)
        fun fromRubles(value: BigDecimal): Money = try {
            Money(value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact())
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Денежная сумма вне допустимого диапазона", error)
        }
    }
}

data class Product(
    val id: UUID,
    val name: String,
    val article: String?,
    val normalizedArticle: String?,
    val brand: String?,
    val aliases: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
)

data class SettlementGroup(
    val id: UUID,
    val number: Long,
    val state: GroupState,
    val costsConfirmed: Boolean,
    val createdAt: Instant,
    val completedAt: Instant?,
    val updatedAt: Instant,
    val revision: Long,
)

data class Order(
    val id: UUID,
    val number: Long,
    val date: LocalDate,
    val counterparty: String,
    val groupId: UUID,
    val receivedPayment: Money,
    val comment: String,
    val costsConfirmed: Boolean,
    val calculationState: CalculationState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
    val manualDeliveryPrice: Money? = null,
    val orderRate: BigDecimal? = null,
    val fulfillment: OrderFulfillment = OrderFulfillment(),
)

data class OrderLine(
    val id: UUID,
    val orderId: UUID,
    val productId: UUID,
    val productNameSnapshot: String,
    val articleSnapshot: String?,
    val quantity: BigDecimal,
    val saleUnitPrice: Money,
    val purchaseUnitPrice: BigDecimal?,
    val purchaseCurrency: Currency?,
    val purchaseRate: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
    val deliveryWeight: BigDecimal? = null,
    val deliveryTariff: BigDecimal? = null,
    val deliveryRate: BigDecimal? = null,
    val deliveryPlanDate: LocalDate? = null,
)

data class CostEntry(
    val id: UUID,
    val ownerType: CostOwnerType,
    val ownerId: UUID,
    val kind: CostKind,
    val amount: BigDecimal,
    val currency: Currency,
    val rate: BigDecimal,
    val isConfirmed: Boolean,
    val date: LocalDate,
    val comment: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
)

data class PriceRecord(
    val id: UUID,
    val productId: UUID,
    val sourceLineId: UUID,
    val orderId: UUID,
    val type: PriceEventType,
    val eventAt: Instant,
    val price: BigDecimal,
    val currency: Currency,
    val rate: BigDecimal,
    val quantity: BigDecimal,
    val preliminary: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
)

data class ChangeEvent(
    val id: UUID,
    val entityType: String,
    val entityId: UUID,
    val changeType: String,
    val eventAt: Instant,
    val localUser: String,
    val beforeJson: String?,
    val afterJson: String?,
)

data class OrderDetails(
    val order: Order,
    val group: SettlementGroup,
    val lines: List<OrderLine>,
    val costs: List<CostEntry>,
    val history: List<ChangeEvent>,
)

data class OrderSummary(
    val order: Order,
    val groupNumber: Long,
    val groupState: GroupState,
    val composition: String,
    val sales: Money,
    val calculation: OrderCalculation,
)

data class GroupSummary(
    val group: SettlementGroup,
    val orderCount: Int,
    val sales: Money,
    val receivedPayment: Money,
    val knownCosts: Money,
    val incompleteCount: Int,
    val preliminaryProfit: Money,
    val isComplete: Boolean,
)

data class GroupDetails(
    val summary: GroupSummary,
    val orders: List<OrderSummary>,
    val costs: List<CostEntry>,
)

data class PriceRecordView(
    val record: PriceRecord,
    val orderNumber: Long,
    val unitWeight: BigDecimal? = null,
)

data class ProductPriceHistory(
    val product: Product,
    val records: List<PriceRecordView>,
) {
    val latestSale: PriceRecordView? = records.firstOrNull { it.record.type == PriceEventType.SALE }
    val latestPurchase: PriceRecordView? = records.firstOrNull { it.record.type == PriceEventType.PURCHASE }
}

data class OrderCalculation(
    val sales: Money,
    val knownPurchaseCosts: Money,
    val knownAdditionalCosts: Money,
    val knownCost: Money,
    val preliminaryProfitWithoutGroupCosts: Money,
    val isComplete: Boolean,
)

data class CostDraft(
    val id: UUID? = null,
    val kind: CostKind = CostKind.OTHER,
    val amount: BigDecimal,
    val currency: Currency = Currency.RUB,
    val rate: BigDecimal = BigDecimal.ONE,
    val isConfirmed: Boolean = true,
    val date: LocalDate,
    val comment: String = "",
)

data class OrderLineDraft(
    val id: UUID? = null,
    val productId: UUID? = null,
    val productName: String,
    val article: String? = null,
    val quantity: BigDecimal,
    val saleUnitPrice: BigDecimal,
    val zeroSalePriceConfirmed: Boolean = false,
    val purchaseUnitPrice: BigDecimal? = null,
    val purchaseCurrency: Currency? = null,
    val purchaseRate: BigDecimal? = null,
    val costs: List<CostDraft> = emptyList(),
    val deliveryWeight: BigDecimal? = null,
    val deliveryTariff: BigDecimal? = null,
    val deliveryRate: BigDecimal? = null,
    val deliveryPlanDate: LocalDate? = null,
)

data class SaveOrderCommand(
    val commandId: UUID,
    val orderId: UUID? = null,
    val date: LocalDate,
    val counterparty: String,
    val receivedPayment: BigDecimal,
    val comment: String = "",
    val costsConfirmed: Boolean,
    val lines: List<OrderLineDraft>,
    val costs: List<CostDraft>,
    val manualDeliveryPrice: BigDecimal? = null,
    val orderRate: BigDecimal? = null,
    val initialFulfillment: OrderFulfillment = OrderFulfillment(),
)

data class SaveGroupCostsCommand(
    val groupId: UUID,
    val costsConfirmed: Boolean,
    val costs: List<CostDraft>,
)

data class FinishGroupCommand(
    val commandId: UUID,
    val groupId: UUID,
)

data class FinishGroupResult(
    val completedGroupId: UUID,
    val nextGroupId: UUID,
    val wasRepeated: Boolean,
)

class DomainValidationException(message: String) : IllegalArgumentException(message)
