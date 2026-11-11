package ru.liftzip.orders.domain

import org.junit.Assert.*
import org.junit.Test
import ru.liftzip.orders.domain.delivery.*
import ru.liftzip.orders.domain.calculation.OrderCalculator
import ru.liftzip.orders.domain.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class DeliveryCalculatorTest {
    private val order=UUID.randomUUID()
    private val day=LocalDate.parse("2026-11-11")
    private fun line(w:String?="12",t:String?="25",r:String?="13") = OrderLine(
        UUID.randomUUID(),order,UUID.randomUUID(),"Товар",null,BigDecimal("2"),Money(250000),BigDecimal("100"),Currency.CNY,BigDecimal("13.25"),Instant.EPOCH,Instant.EPOCH,1,
        w?.toBigDecimal(),t?.toBigDecimal(),r?.toBigDecimal(),if(w==null)null else day,
    )
    @Test fun forecastAndActualUseIndependentRates() {
        val d=DeliveryDetails(DeliveryInvoice(order,Money(443750),day,BigDecimal("12.5"),BigDecimal("25"),BigDecimal("14.2")),emptyList(),listOf(line()))
        assertEquals(Money(780000),d.planned);assertEquals(Money(-336250),d.difference)
        assertEquals(Money(443750),DeliveryCalculator.amount(BigDecimal("12.5"),BigDecimal("25"),BigDecimal("14.2")))
        assertEquals(Money(780000),d.planned)
    }
    @Test fun paymentsRemainSeparateAndPartial() {
        val client=DeliveryPayment(UUID.randomUUID(),order,DeliveryParty.CLIENT,Money(200000),day,"")
        val logistic=DeliveryPayment(UUID.randomUUID(),order,DeliveryParty.LOGISTICS,Money(443750),day,"")
        val d=DeliveryDetails(DeliveryInvoice(order,Money(443750),day),listOf(client,logistic),listOf(line()))
        assertEquals(Money(243750),d.clientBalance);assertEquals(Money.ZERO,d.logisticsBalance)
    }
    @Test fun reversalRestoresBalanceWithoutDeletingHistory() {
        val p=DeliveryPayment(UUID.randomUUID(),order,DeliveryParty.CLIENT,Money(443750),day,"")
        val d=DeliveryDetails(DeliveryInvoice(order,Money(443750),day),listOf(p,p.copy(id=UUID.randomUUID(),amount=Money(-443750),reversalOf=p.id)),listOf(line()))
        assertEquals(Money.ZERO,d.clientPaid);assertEquals(Money(443750),d.clientBalance)
    }
    @Test fun noInvoiceDoesNotInventDebtFromForecast() {
        val d=DeliveryDetails(DeliveryInvoice(order),emptyList(),listOf(line()))
        assertNull(d.clientBalance);assertNull(d.logisticsBalance);assertNull(d.difference)
    }
    @Test fun missingWeightIsUnknownRatherThanZero() { assertNull(DeliveryCalculator.totalPlan(listOf(line(w=null,t=null,r=null)))) }
    @Test fun unitWeightIsMultipliedByQuantity() { assertEquals(Money(780000),DeliveryCalculator.totalPlan(listOf(line()))) }
    @Test fun invalidPlanIsRejectedButPartialIsAllowed() {
        DeliveryCalculator.validatePlan(BigDecimal.ONE,null,BigDecimal.ONE,day)
        assertThrows(IllegalArgumentException::class.java){DeliveryCalculator.validatePlan(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,day)}
    }
    @Test fun logisticsDoesNotChangeGoodsProfit() {
        val o=Order(order,1,day,"Денис",UUID.randomUUID(),Money(500000),"",true,CalculationState.PRELIMINARY,Instant.EPOCH,Instant.EPOCH,1)
        val result=OrderCalculator.calculate(o,listOf(line()),emptyList())
        assertEquals(Money.ZERO,result.knownCost);assertEquals(Money.ZERO,result.preliminaryProfitWithoutGroupCosts);assertFalse(result.isComplete)
    }
    @Test fun invoiceDoesNotRequireGuessedWeightOrRate() { DeliveryCalculator.validateInvoice(DeliveryInvoice(order,Money(443750),day)) }
    @Test fun netSumDoesNotDependOnJournalDateOrder() {
        assertEquals(Money(Long.MAX_VALUE),DeliveryCalculator.sumPayments(listOf(Money(Long.MAX_VALUE),Money(Long.MAX_VALUE),Money(-Long.MAX_VALUE))))
        assertThrows(ArithmeticException::class.java) { DeliveryCalculator.sumPayments(listOf(Money(Long.MAX_VALUE),Money(1))) }
    }
    @Test fun overpaymentIsCreditRatherThanNegativeDebtHiddenByClamp() {
        val d=DeliveryDetails(DeliveryInvoice(order,Money(10000),day),listOf(DeliveryPayment(UUID.randomUUID(),order,DeliveryParty.CLIENT,Money(11000),day,"")),listOf(line()))
        assertEquals(Money(-1000),d.clientBalance)
    }
}
