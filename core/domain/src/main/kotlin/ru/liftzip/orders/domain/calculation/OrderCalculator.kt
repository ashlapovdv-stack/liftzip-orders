package ru.liftzip.orders.domain.calculation

import ru.liftzip.orders.domain.model.CostEntry
import ru.liftzip.orders.domain.model.CostOwnerType
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupDetails
import ru.liftzip.orders.domain.model.GroupSummary
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.Order
import ru.liftzip.orders.domain.model.OrderCalculation
import ru.liftzip.orders.domain.model.OrderLine
import ru.liftzip.orders.domain.model.SettlementGroup
import java.math.BigDecimal
import java.math.RoundingMode

object OrderCalculator {
    fun lineSale(line: OrderLine): Money = money(
        line.quantity.multiply(line.saleUnitPrice.toBigDecimal()),
    )

    fun linePurchase(line: OrderLine): Money? {
        val price = line.purchaseUnitPrice ?: return null
        val currency = line.purchaseCurrency ?: return null
        val rate = line.purchaseRate ?: return null
        return money(line.quantity.multiply(price).multiply(rateFor(currency, rate)))
    }

    fun costInRub(cost: CostEntry): Money = money(cost.amount.multiply(rateFor(cost.currency, cost.rate)))

    fun calculate(
        order: Order,
        lines: List<OrderLine>,
        costs: List<CostEntry>,
    ): OrderCalculation {
        val lineIds = lines.mapTo(hashSetOf()) { it.id }
        val applicableCosts = costs.filter {
            (it.ownerType == CostOwnerType.ORDER && it.ownerId == order.id) ||
                (it.ownerType == CostOwnerType.LINE && it.ownerId in lineIds)
        }
        val priceForDenis = lines.fold(Money.ZERO) { total, line -> total + lineSale(line) }
        // The agreed formulas have one source for each value. Per-line purchases,
        // group expenses and delivery estimates stay reference data only.
        val purchases = applicableCosts.filter {
            it.ownerType == CostOwnerType.ORDER && it.kind == CostKind.PURCHASE
        }
        val purchaseEntry = purchases.singleOrNull()
        val purchase = purchaseEntry?.let(::costInRub)
        val income = order.manualDeliveryPrice?.let { priceForDenis - it }
        val complete = income != null && purchase != null && purchaseEntry?.isConfirmed == true
        return OrderCalculation(
            priceForDenis = priceForDenis,
            income = income,
            purchaseCost = purchase,
            profit = if (complete) income!! - purchase!! else null,
            isComplete = complete,
        )
    }

    fun summarizeGroup(
        group: SettlementGroup,
        orders: List<Triple<Order, List<OrderLine>, List<CostEntry>>>,
        groupCosts: List<CostEntry>,
    ): GroupSummary {
        val calculations = orders.map { (order, lines, costs) -> calculate(order, lines, costs) }
        return GroupSummary(
            group = group,
            orderCount = orders.size,
            income = totalOrUnknown(calculations.map { it.income }),
            receivedPayment = orders.fold(Money.ZERO) { sum, item -> sum + item.first.receivedPayment },
            purchaseCost = totalOrUnknown(calculations.map { it.purchaseCost }),
            incompleteCount = calculations.count { !it.isComplete },
            profit = totalOrUnknown(calculations.map { it.profit }),
            isComplete = calculations.all { it.isComplete },
        )
    }

    private fun totalOrUnknown(values: List<Money?>): Money? =
        if (values.any { it == null }) null else values.fold(Money.ZERO) { sum, value -> sum + value!! }

    private fun rateFor(currency: Currency, rate: BigDecimal): BigDecimal {
        require(rate > BigDecimal.ZERO) { "Курс должен быть положительным" }
        require(currency != Currency.RUB || rate.compareTo(BigDecimal.ONE) == 0) {
            "Для RUB курс равен 1"
        }
        return rate
    }

    private fun money(value: BigDecimal): Money = Money.fromRubles(value)
}

fun Iterable<BigDecimal>.roundedLineSum(): BigDecimal = fold(BigDecimal.ZERO) { sum, value ->
    sum.add(value.setScale(2, RoundingMode.HALF_UP))
}
