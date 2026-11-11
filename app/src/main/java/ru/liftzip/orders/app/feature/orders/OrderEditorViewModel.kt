package ru.liftzip.orders.app.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.liftzip.orders.app.ui.parseDecimal
import ru.liftzip.orders.domain.calculation.OrderCalculator
import ru.liftzip.orders.domain.calculation.OrderValidator
import ru.liftzip.orders.domain.model.CalculationState
import ru.liftzip.orders.domain.model.ChangeEvent
import ru.liftzip.orders.domain.model.CostDraft
import ru.liftzip.orders.domain.model.CostEntry
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.CostOwnerType
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.Order
import ru.liftzip.orders.domain.model.OrderCalculation
import ru.liftzip.orders.domain.model.OrderLine
import ru.liftzip.orders.domain.model.OrderLineDraft
import ru.liftzip.orders.domain.model.Product
import ru.liftzip.orders.domain.model.SaveOrderCommand
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class EditorCostDraft(
    val id: UUID? = null,
    val kind: CostKind = CostKind.OTHER,
    val amount: String = "",
    val currency: Currency = Currency.RUB,
    val rate: String = "1",
    val isConfirmed: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val comment: String = "",
)

data class EditorLineDraft(
    val id: UUID? = null,
    val productId: UUID? = null,
    val name: String = "",
    val article: String = "",
    val quantity: String = "1",
    val salePrice: String = "",
    val zeroSalePriceConfirmed: Boolean = false,
    val purchasePrice: String = "",
    val purchaseCurrency: Currency = Currency.RUB,
    val purchaseRate: String = "1",
    val costs: List<EditorCostDraft> = emptyList(),
    val deliveryWeight: String = "",
    val deliveryTariff: String = "",
    val deliveryRate: String = "",
    val deliveryPlanDate: LocalDate = LocalDate.now(),
)

data class OrderEditorState(
    val loaded: Boolean = false,
    val orderId: UUID? = null,
    val orderNumber: Long? = null,
    val groupId: UUID? = null,
    val groupNumber: Long? = null,
    val groupState: GroupState = GroupState.FORMING,
    val date: LocalDate = LocalDate.now(),
    val counterparty: String = "",
    val payment: String = "",
    val comment: String = "",
    val costsConfirmed: Boolean = false,
    val manualDeliveryPrice: String = "",
    val purchaseTotal: String = "",
    val purchaseCurrency: Currency = Currency.RUB,
    val purchaseRate: String = "1",
    val orderRate: String = "",
    val lines: List<EditorLineDraft> = listOf(EditorLineDraft()),
    val costs: List<EditorCostDraft> = emptyList(),
    val history: List<ChangeEvent> = emptyList(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val showLeaveConfirmation: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val generalError: String? = null,
) {
    val salesLocked: Boolean = groupState != GroupState.FORMING
}

class OrderEditorViewModel(
    private val repository: OrdersRepository,
    private val existingOrderId: UUID?,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderEditorState(orderId = existingOrderId))
    val state = _state.asStateFlow()
    private var commandId = UUID.randomUUID()
    private val calculationOrderId = existingOrderId ?: UUID.randomUUID()

    init {
        if (existingOrderId == null) {
            _state.update { it.copy(loaded = true) }
        } else {
            viewModelScope.launch {
                val details = repository.observeOrder(existingOrderId).first()
                if (details == null) {
                    _state.update { it.copy(loaded = true, generalError = "Заказ не найден") }
                } else {
                    _state.update {
                        OrderEditorState(
                            loaded = true, orderId = details.order.id, orderNumber = details.order.number,
                            groupId = details.group.id, groupNumber = details.group.number, groupState = details.group.state,
                            date = details.order.date, counterparty = details.order.counterparty,
                            payment = details.order.receivedPayment.toBigDecimal().toPlainString(),
                            comment = details.order.comment, costsConfirmed = details.order.costsConfirmed,
                            manualDeliveryPrice = details.order.manualDeliveryPrice?.toBigDecimal()?.toPlainString().orEmpty(),
                            orderRate = details.order.orderRate?.toPlainString() ?: details.lines.firstOrNull { it.deliveryRate != null }?.deliveryRate?.toPlainString().orEmpty(),
                            purchaseTotal = details.costs.singleOrNull { it.ownerType == CostOwnerType.ORDER && it.kind == CostKind.PURCHASE }?.let { cost ->
                                (if (cost.currency == Currency.CNY) cost.amount.multiply(cost.rate) else cost.amount)
                                    .stripTrailingZeros().toPlainString()
                            }.orEmpty(),
                            purchaseCurrency = Currency.RUB,
                            purchaseRate = "1",
                            orderRate = details.order.orderRate?.toPlainString() ?: details.lines.firstNotNullOfOrNull { it.deliveryRate }?.toPlainString().orEmpty(),
                            lines = details.lines.map { line ->
                                toEditorLine(line).copy(
                                    costs = details.costs.filter {
                                        it.ownerType == CostOwnerType.LINE && it.ownerId == line.id
                                    }.map(::toEditorCost),
                                )
                            },
                            costs = details.costs.filter { it.ownerType == CostOwnerType.ORDER }.map(::toEditorCost),
                            history = details.history,
                        )
                    }
                }
            }
        }
    }

    fun update(transform: (OrderEditorState) -> OrderEditorState) {
        _state.update { transform(it).copy(dirty = true, generalError = null) }
    }

    fun updateLine(index: Int, transform: (EditorLineDraft) -> EditorLineDraft) = update { state ->
        state.copy(lines = state.lines.mapIndexed { i, line -> if (i == index) transform(line) else line })
    }

    fun addLine() = update { it.copy(lines = it.lines + EditorLineDraft()) }
    fun removeLine(index: Int) = update { state ->
        if (state.lines.size <= 1) state else state.copy(lines = state.lines.filterIndexed { i, _ -> i != index })
    }

    fun selectProduct(index: Int, product: Product) = updateLine(index) {
        it.copy(productId = product.id, name = product.name, article = product.article.orEmpty())
    }

    fun addOrderCost() = update { it.copy(costs = it.costs + EditorCostDraft()) }
    fun updateOrderCost(index: Int, transform: (EditorCostDraft) -> EditorCostDraft) = update { state ->
        state.copy(costs = state.costs.mapIndexed { i, cost -> if (i == index) transform(cost) else cost })
    }
    fun removeOrderCost(index: Int) = update { it.copy(costs = it.costs.filterIndexed { i, _ -> i != index }) }

    fun addLineCost(lineIndex: Int) = updateLine(lineIndex) { it.copy(costs = it.costs + EditorCostDraft()) }
    fun updateLineCost(lineIndex: Int, costIndex: Int, transform: (EditorCostDraft) -> EditorCostDraft) =
        updateLine(lineIndex) { line ->
            line.copy(costs = line.costs.mapIndexed { i, cost -> if (i == costIndex) transform(cost) else cost })
        }
    fun removeLineCost(lineIndex: Int, costIndex: Int) = updateLine(lineIndex) {
        it.copy(costs = it.costs.filterIndexed { i, _ -> i != costIndex })
    }

    fun requestBack(): Boolean {
        if (!_state.value.dirty || _state.value.saving) return true
        _state.update { it.copy(showLeaveConfirmation = true) }
        return false
    }
    fun keepEditing() = _state.update { it.copy(showLeaveConfirmation = false) }
    fun discardChanges() = _state.update { it.copy(showLeaveConfirmation = false, dirty = false) }

    fun save(onSaved: (UUID) -> Unit) {
        val snapshot = _state.value
        if (snapshot.saving) return
        val command = buildCommand(snapshot) ?: return
        _state.update { it.copy(saving = true, generalError = null, errors = emptyMap()) }
        viewModelScope.launch {
            try {
                val id = repository.saveOrder(command)
                _state.update { it.copy(saving = false, dirty = false) }
                commandId = UUID.randomUUID()
                onSaved(id)
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        saving = false,
                        generalError = error.message ?: "Не удалось сохранить заказ",
                        errors = if (error.message?.contains("оплата", ignoreCase = true) == true)
                            mapOf("payment" to error.message.orEmpty()) else emptyMap(),
                    )
                }
            }
        }
    }

    fun preview(): OrderCalculation? = try {
        val state = _state.value
        val lines = state.lines.mapIndexed { index, line -> line.toDomain(calculationOrderId, index) }
        val order = Order(
            id = calculationOrderId, number = state.orderNumber ?: 0, date = state.date,
            counterparty = state.counterparty, groupId = state.groupId ?: calculationOrderId,
            receivedPayment = state.payment.parseDecimal()?.let { Money.fromRubles(it) } ?: Money.ZERO,
            comment = state.comment, costsConfirmed = state.costsConfirmed,
            calculationState = CalculationState.INCOMPLETE, createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH, revision = 0,
            manualDeliveryPrice = state.manualDeliveryPrice.parseDecimal()?.let(Money::fromRubles),
            orderRate = state.orderRate.parseDecimal(),
            orderRate = state.orderRate.parseDecimal(),
        )
        val costs = buildList {
            state.lines.forEachIndexed { lineIndex, line ->
                line.costs.forEach { cost -> add(cost.toDomain(CostOwnerType.LINE, lines[lineIndex].id, state.date)) }
            }
            state.costs.filter { it.kind != CostKind.PURCHASE }.forEach { add(it.toDomain(CostOwnerType.ORDER, calculationOrderId, state.date)) }
            state.aggregatePurchaseCost()?.let { add(it.toDomain(CostOwnerType.ORDER, calculationOrderId, state.date)) }
        }
        OrderCalculator.calculate(order, lines, costs)
    } catch (_: Exception) {
        null
    }

    private fun buildCommand(state: OrderEditorState): SaveOrderCommand? {
        val errors = mutableMapOf<String, String>()
        if (state.counterparty.isBlank()) errors["counterparty"] = "Укажите клиента или менеджера"
        val payment = state.payment.parseDecimal() ?: BigDecimal.ZERO
        val lines = state.lines.mapIndexed { index, line ->
            val name = line.name.trim()
            if (name.isEmpty()) errors["line_${index}_name"] = "Укажите название"
            val quantity = line.quantity.parseDecimal()
            if (quantity == null || quantity <= BigDecimal.ZERO) errors["line_${index}_quantity"] = "Количество больше нуля"
            val sale = line.salePrice.parseDecimal()
            if (line.salePrice.isBlank() || sale == null) errors["line_${index}_sale"] = "Укажите цену продажи"
            if (sale?.compareTo(BigDecimal.ZERO) == 0 && !line.zeroSalePriceConfirmed) {
                errors["line_${index}_zero"] = "Подтвердите осознанную нулевую цену"
            }
            if (listOf(line.deliveryWeight, line.deliveryTariff).any { it.isNotBlank() && it.parseDecimal() == null }) {
                errors["line_${index}_delivery"] = "Проверьте числовые значения доставки"
            }
            val purchase = line.purchasePrice.parseDecimal()
            if (line.purchasePrice.isNotBlank() && purchase == null) {
                errors["line_${index}_purchase"] = "Некорректная закупочная цена"
            }
            val rate = if (line.purchaseCurrency == Currency.RUB) BigDecimal.ONE else state.orderRate.parseDecimal()
            if (purchase != null && line.purchaseCurrency == Currency.CNY && (rate == null || rate <= BigDecimal.ZERO)) errors["line_${index}_rate"] = "Укажите курс заказа"
            OrderLineDraft(
                id = line.id, productId = line.productId, productName = name,
                article = line.article.ifBlank { null }, quantity = quantity ?: BigDecimal.ONE,
                saleUnitPrice = sale ?: BigDecimal.ZERO, zeroSalePriceConfirmed = line.zeroSalePriceConfirmed,
                purchaseUnitPrice = purchase,
                purchaseCurrency = if (purchase == null) null else line.purchaseCurrency,
                purchaseRate = if (purchase == null) null else rate,
                deliveryWeight = line.deliveryWeight.parseDecimal(), deliveryTariff = line.deliveryTariff.parseDecimal(),
                deliveryRate = state.orderRate.parseDecimal(), deliveryPlanDate = if (line.deliveryWeight.isBlank() && line.deliveryTariff.isBlank()) null else line.deliveryPlanDate,
                costs = line.costs.mapIndexedNotNull { costIndex, cost ->
                    cost.toDomainOrNull(state.date, errors, "line_${index}_cost_$costIndex")
                },
            )
        }
        if (state.manualDeliveryPrice.isNotBlank() && (state.manualDeliveryPrice.parseDecimal() == null || state.manualDeliveryPrice.parseDecimal()!! < BigDecimal.ZERO)) errors["manualDeliveryPrice"] = "Укажите корректную цену доставки"
        val costs = state.costs.filter { it.kind != CostKind.PURCHASE }.mapIndexedNotNull { index, cost -> cost.toDomainOrNull(state.date, errors, "cost_$index") }.toMutableList()
        if (state.purchaseTotal.isNotBlank()) {
            val total = state.purchaseTotal.parseDecimal()
            val rate = BigDecimal.ONE
            if (total == null || total < BigDecimal.ZERO) errors["purchaseTotal"] = "Укажите сумму закупки заказа"
            if (rate == null || rate <= BigDecimal.ZERO) errors["purchaseRate"] = "Укажите курс закупки"
            if (total != null && total >= BigDecimal.ZERO && rate != null && rate > BigDecimal.ZERO) {
                costs += CostDraft(id = state.costs.firstOrNull { it.kind == CostKind.PURCHASE && it.id != null }?.id,
                    kind = CostKind.PURCHASE, amount = total, currency = Currency.RUB,
                    rate = rate, isConfirmed = true, date = state.date)
            }
        }
        if (state.orderRate.isNotBlank() && (state.orderRate.parseDecimal() == null || state.orderRate.parseDecimal()!! <= BigDecimal.ZERO)) errors["orderRate"] = "Укажите положительный курс"
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, generalError = "Проверьте выделенные поля") }
            return null
        }
        val command = SaveOrderCommand(
            commandId = commandId, orderId = state.orderId, date = state.date,
            counterparty = state.counterparty.trim(), receivedPayment = payment,
            comment = state.comment, costsConfirmed = state.costsConfirmed, lines = lines, costs = costs,
            manualDeliveryPrice = state.manualDeliveryPrice.parseDecimal(),
            orderRate = orderRate,
            orderRate = state.orderRate.parseDecimal(),
        )
        try {
            OrderValidator.validate(command)
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    generalError = error.message,
                    errors = if (error.message?.contains("оплата", true) == true)
                        mapOf("payment" to error.message.orEmpty()) else emptyMap(),
                )
            }
            return null
        }
        return command
    }

    private fun OrderEditorState.aggregatePurchaseCost(): EditorCostDraft? {
        if (purchaseTotal.isBlank()) return null
        val rate = "1"
        return EditorCostDraft(id = costs.firstOrNull { it.kind == CostKind.PURCHASE }?.id,
            kind = CostKind.PURCHASE, amount = purchaseTotal, currency = Currency.RUB,
            rate = rate, date = date)
    }

    private fun EditorCostDraft.toDomainOrNull(
        date: LocalDate,
        errors: MutableMap<String, String>,
        prefix: String,
    ): CostDraft? {
        val parsedAmount = amount.parseDecimal()
        val parsedRate = rate.parseDecimal()
        if (amount.isBlank() || parsedAmount == null || parsedAmount < BigDecimal.ZERO) {
            errors["${prefix}_amount"] = "Укажите неотрицательную сумму"
        }
        if (parsedRate == null || parsedRate <= BigDecimal.ZERO) errors["${prefix}_rate"] = "Курс должен быть положительным"
        if (currency == Currency.RUB && parsedRate != null && parsedRate.compareTo(BigDecimal.ONE) != 0) {
            errors["${prefix}_rate"] = "Для RUB курс равен 1"
        }
        return if (parsedAmount == null || parsedRate == null) null else CostDraft(
            id = id, kind = kind, amount = parsedAmount, currency = currency, rate = parsedRate,
            isConfirmed = isConfirmed, date = this.date, comment = comment,
        )
    }

    private fun EditorCostDraft.toDomain(owner: CostOwnerType, ownerId: UUID, date: LocalDate) = CostEntry(
        id = id ?: UUID.randomUUID(), ownerType = owner, ownerId = ownerId, kind = kind,
        amount = amount.parseDecimal() ?: BigDecimal.ZERO, currency = currency,
        rate = rate.parseDecimal() ?: BigDecimal.ONE, isConfirmed = isConfirmed, date = this.date,
        comment = comment, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, revision = 0,
    )

    private fun EditorLineDraft.toDomain(orderId: UUID, index: Int) = OrderLine(
        id = id ?: UUID.nameUUIDFromBytes("$orderId-preview-$index".toByteArray()), orderId = orderId,
        productId = productId ?: orderId, productNameSnapshot = name, articleSnapshot = article.ifBlank { null },
        quantity = quantity.parseDecimal() ?: BigDecimal.ONE,
        saleUnitPrice = Money.fromRubles(salePrice.parseDecimal() ?: BigDecimal.ZERO),
        purchaseUnitPrice = purchasePrice.parseDecimal(),
        purchaseCurrency = if (purchasePrice.isBlank()) null else purchaseCurrency,
        purchaseRate = if (purchasePrice.isBlank()) null else if (purchaseCurrency == Currency.RUB) BigDecimal.ONE else _state.value.orderRate.parseDecimal(),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, revision = 0,
        deliveryWeight = deliveryWeight.parseDecimal(), deliveryTariff = deliveryTariff.parseDecimal(),
        deliveryRate = _state.value.orderRate.parseDecimal(), deliveryPlanDate = if(deliveryWeight.isBlank()) null else deliveryPlanDate,
    )

    private fun toEditorLine(line: OrderLine) = EditorLineDraft(
        id = line.id, productId = line.productId, name = line.productNameSnapshot,
        article = line.articleSnapshot.orEmpty(), quantity = line.quantity.stripTrailingZeros().toPlainString(),
        salePrice = line.saleUnitPrice.toBigDecimal().toPlainString(), zeroSalePriceConfirmed = true,
        purchasePrice = line.purchaseUnitPrice?.toPlainString().orEmpty(),
        purchaseCurrency = line.purchaseCurrency ?: Currency.RUB,
        purchaseRate = line.purchaseRate?.toPlainString() ?: "1",
        costs = emptyList(), deliveryWeight = line.deliveryWeight?.toPlainString().orEmpty(),
        deliveryTariff = line.deliveryTariff?.toPlainString().orEmpty(), deliveryRate = "",
        deliveryPlanDate = line.deliveryPlanDate ?: LocalDate.now(),
    )

    fun deliveryPreview(): Money? = try {
        val state=_state.value
        ru.liftzip.orders.domain.delivery.DeliveryCalculator.totalPlan(state.lines.mapIndexed { i,l -> l.toDomain(calculationOrderId,i) })
    } catch (_:Exception) { null }

    private fun toEditorCost(cost: CostEntry) = EditorCostDraft(
        id = cost.id, kind = cost.kind, amount = cost.amount.toPlainString(), currency = cost.currency,
        rate = cost.rate.toPlainString(), isConfirmed = cost.isConfirmed, date = cost.date, comment = cost.comment,
    )
}
