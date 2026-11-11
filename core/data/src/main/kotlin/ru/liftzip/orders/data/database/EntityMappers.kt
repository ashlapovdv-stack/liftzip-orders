package ru.liftzip.orders.data.database

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ru.liftzip.orders.domain.model.CalculationState
import ru.liftzip.orders.domain.model.ChangeEvent
import ru.liftzip.orders.domain.model.CostEntry
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.CostOwnerType
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.Order
import ru.liftzip.orders.domain.model.OrderLine
import ru.liftzip.orders.domain.model.PriceEventType
import ru.liftzip.orders.domain.model.PriceRecord
import ru.liftzip.orders.domain.model.Product
import ru.liftzip.orders.domain.model.SettlementGroup
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val aliasesSerializer = ListSerializer(String.serializer())

fun String.uuid(): UUID = UUID.fromString(this)
fun String.decimal(): BigDecimal = BigDecimal(this)
fun BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()
fun Long.instant(): Instant = Instant.ofEpochMilli(this)
fun Instant.epoch(): Long = toEpochMilli()

fun ProductEntity.toDomain(): Product = Product(
    id = id.uuid(), name = name, article = article, normalizedArticle = normalizedArticle,
    brand = brand, aliases = Json.decodeFromString(aliasesSerializer, aliasesJson),
    createdAt = createdAt.instant(), updatedAt = updatedAt.instant(), revision = revision,
)

fun SettlementGroupEntity.toDomain(): SettlementGroup = SettlementGroup(
    id = id.uuid(), number = number, state = GroupState.valueOf(state), costsConfirmed = costsConfirmed,
    createdAt = createdAt.instant(), completedAt = completedAt?.instant(), updatedAt = updatedAt.instant(),
    revision = revision,
)

fun OrderEntity.toDomain(): Order = Order(
    id = id.uuid(), number = number, date = LocalDate.parse(date), counterparty = counterparty,
    groupId = groupId.uuid(), receivedPayment = Money(receivedPaymentMinor), comment = comment,
    costsConfirmed = costsConfirmed, calculationState = CalculationState.valueOf(calculationState),
    createdAt = createdAt.instant(), updatedAt = updatedAt.instant(), revision = revision,
    manualDeliveryPrice = manualDeliveryPriceMinor?.let(::Money),
    orderRate = orderRate?.decimal(),
)

fun OrderLineEntity.toDomain(): OrderLine = OrderLine(
    id = id.uuid(), orderId = orderId.uuid(), productId = productId.uuid(),
    productNameSnapshot = productNameSnapshot, articleSnapshot = articleSnapshot,
    quantity = quantity.decimal(), saleUnitPrice = Money(saleUnitPriceMinor),
    purchaseUnitPrice = purchaseUnitPrice?.decimal(), purchaseCurrency = purchaseCurrency?.let(Currency::valueOf),
    purchaseRate = purchaseRate?.decimal(), createdAt = createdAt.instant(), updatedAt = updatedAt.instant(),
    revision = revision, deliveryWeight = deliveryWeight?.decimal(), deliveryTariff = deliveryTariff?.decimal(),
    deliveryRate = deliveryRate?.decimal(), deliveryPlanDate = deliveryPlanDate?.let(LocalDate::parse),
)

fun CostEntryEntity.toDomain(): CostEntry = CostEntry(
    id = id.uuid(), ownerType = CostOwnerType.valueOf(ownerType), ownerId = ownerId.uuid(),
    kind = CostKind.valueOf(kind), amount = amount.decimal(), currency = Currency.valueOf(currency),
    rate = rate.decimal(), isConfirmed = isConfirmed, date = LocalDate.parse(date), comment = comment,
    createdAt = createdAt.instant(), updatedAt = updatedAt.instant(), revision = revision,
)

fun PriceRecordEntity.toDomain(): PriceRecord = PriceRecord(
    id = id.uuid(), productId = productId.uuid(), sourceLineId = sourceLineId.uuid(), orderId = orderId.uuid(),
    type = PriceEventType.valueOf(type), eventAt = eventAt.instant(), price = price.decimal(),
    currency = Currency.valueOf(currency), rate = rate.decimal(), quantity = quantity.decimal(),
    preliminary = preliminary, createdAt = createdAt.instant(), updatedAt = updatedAt.instant(), revision = revision,
)

fun ChangeEventEntity.toDomain(): ChangeEvent = ChangeEvent(
    id = id.uuid(), entityType = entityType, entityId = entityId.uuid(), changeType = changeType,
    eventAt = eventAt.instant(), localUser = localUser, beforeJson = beforeJson, afterJson = afterJson,
)
