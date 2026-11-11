package ru.liftzip.orders.domain.calculation

import ru.liftzip.orders.domain.model.CostEntry
import ru.liftzip.orders.domain.model.CostOwnerType
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
        val sales = lines.fold(Money.ZERO) { total, line -> total + lineSale(line) }
        val purchase = lines.fold(Money.ZERO) { total, line -> total + (linePurchase(line) ?: Money.ZERO) }
        val additional = applicableCosts.fold(Money.ZERO) { total, cost -> total + costInRub(cost) }
        val knownCost = purchase + additional
        val complete = order.costsConfirmed &&
            lines.all { it.isPurchaseComplete() } &&
            applicableCosts.all { it.isConfirmed }
        return OrderCalculation(
            sales = sales,
            knownPurchaseCosts = purchase,
            knownAdditionalCosts = additional,
            knownCost = knownCost,
            preliminaryProfitWithoutGroupCosts = sales - knownCost,
            isComplete = complete,
        )
    }

    fun summarizeGroup(
        group: SettlementGroup,
        orders: List<Triple<Order, List<OrderLine>, List<CostEntry>>>,
        groupCosts: List<CostEntry>,
    ): GroupSummary {
        val calculations = orders.map { (order, lines, costs) -> calculate(order, lines, costs) }
        val sales = calculations.fold(Money.ZERO) { sum, item -> sum + item.sales }
        val payment = orders.fold(Money.ZERO) { sum, item -> sum + item.first.receivedPayment }
        val orderCosts = calculations.fold(Money.ZERO) { sum, item -> sum + item.knownCost }
        val ownGroupCosts = groupCosts
            .filter { it.ownerType == CostOwnerType.GROUP && it.ownerId == group.id }
            .fold(Money.ZERO) { sum, cost -> sum + costInRub(cost) }
        val knownCosts = orderCosts + ownGroupCosts
        val complete = group.costsConfirmed && groupCosts.all { it.isConfirmed } &&
            calculations.all { it.isComplete }
        return GroupSummary(
            group = group,
            orderCount = orders.size,
            sales = sales,
            receivedPayment = payment,
            knownCosts = knownCosts,
            incompleteCount = calculations.count { !it.isComplete },
            preliminaryProfit = sales - knownCosts,
            isComplete = complete,
        )
    }

    private fun OrderLine.isPurchaseComplete(): Boolean =
        purchaseUnitPrice != null && purchaseCurrency != null && purchaseRate != null

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
