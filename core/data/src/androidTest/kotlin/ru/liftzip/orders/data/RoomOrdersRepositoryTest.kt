package ru.liftzip.orders.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository
import ru.liftzip.orders.domain.model.FinishGroupCommand
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.OrderLineDraft
import ru.liftzip.orders.domain.model.SaveOrderCommand
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomOrdersRepositoryTest {
    private lateinit var database: OrdersDatabase
    private lateinit var repository: RoomOrdersRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OrdersDatabase::class.java,
        ).build()
        repository = RoomOrdersRepository(database)
        runBlocking { repository.initialize() }
    }

    @After fun tearDown() { database.close() }

    @Test fun taobaoUrlSurvivesSaveAndEditWithoutChangingOrderTotals() = runTest {
        val canonical = "https://item.taobao.com/item.htm?id=45184215152"
        val command = orderCommand().copy(lines = listOf(
            orderCommand().lines.single().copy(productUrl = canonical),
        ))
        val id = repository.saveOrder(command)
        val before = repository.observeOrder(id).first()!!
        assertEquals(canonical, before.lines.single().productUrl)
        val line = before.lines.single()
        repository.saveOrder(command.copy(
            commandId = UUID.randomUUID(), orderId = id,
            lines = listOf(command.lines.single().copy(id = line.id, productId = line.productId)),
        ))
        val after = repository.observeOrder(id).first()!!
        assertEquals(canonical, after.lines.single().productUrl)
        assertEquals(before.order.receivedPayment, after.order.receivedPayment)
        assertEquals(before.lines.single().saleUnitPrice, after.lines.single().saleUnitPrice)
    }

    @Test fun saveIsIdempotentAndPriceHistoryDoesNotDuplicate() = runTest {
        val command = orderCommand()
        val id = repository.saveOrder(command)
        assertEquals(id, repository.saveOrder(command))
        assertEquals(1, repository.observeOrders("", ru.liftzip.orders.domain.model.OrdersFilter.ALL, 50).first().size)

        val saved = repository.observeOrder(id).first()!!
        val productId = saved.lines.single().productId
        val lineId = saved.lines.single().id
        val update = command.copy(
            commandId = UUID.randomUUID(), orderId = id,
            lines = listOf(command.lines.single().copy(id = lineId, productId = productId, purchaseUnitPrice = BigDecimal("55"))),
        )
        repository.saveOrder(update)
        val history = repository.observeProductHistory(productId).first()!!
        assertEquals(2, history.records.size)
        assertEquals(1, history.records.count { it.record.type.name == "SALE" })
        assertEquals(BigDecimal("55"), history.records.single { it.record.type.name == "PURCHASE" }.record.price)
    }

    @Test fun priceHistoryUsesWeightOfItsSourceLineAndKeepsUnknownWeight() = runTest {
        val command = orderCommand()
        val ids = listOf("0.2", "1.35", null).map { weight ->
            repository.saveOrder(command.copy(commandId = UUID.randomUUID(), orderRate = BigDecimal("10"),
                lines = listOf(command.lines.single().copy(
                    deliveryWeight = weight?.let(::BigDecimal),
                    deliveryTariff = weight?.let { BigDecimal("55") },
                    deliveryPlanDate = weight?.let { LocalDate.parse("2026-11-11") },
                ))))
        }
        val productId = repository.observeOrder(ids.first()).first()!!.lines.single().productId
        val history = repository.observeProductHistory(productId).first()!!
        assertEquals(6, history.records.size)
        assertEquals(3, history.lines.size)
        assertTrue(history.lines.all { it.sale != null && it.purchase != null })
        listOf(BigDecimal("0.2"), BigDecimal("1.35"), null).forEachIndexed { index, expected ->
            val records = history.records.filter { it.record.orderId == ids[index] }
            assertEquals(2, records.size)
            records.forEach { assertEquals(expected, it.unitWeight) }
        }
        val saved = repository.observeOrder(ids.first()).first()!!
        val line = saved.lines.single()
        repository.saveOrder(command.copy(commandId = UUID.randomUUID(), orderId = ids.first(), orderRate = BigDecimal("10"),
            lines = listOf(command.lines.single().copy(id = line.id, productId = productId,
                deliveryWeight = BigDecimal("0.45"), deliveryTariff = BigDecimal("55"), deliveryPlanDate = LocalDate.parse("2026-11-11")))))
        val updated = repository.observeProductHistory(productId).first()!!
        assertTrue(updated.records.filter { it.record.orderId == ids.first() }.all { it.unitWeight == BigDecimal("0.45") })
        assertEquals(6, updated.records.size)
    }

    @Test fun finishIsAtomicAndRepeatedCommandDoesNotCreateThirdGroup() = runTest {
        repository.saveOrder(orderCommand())
        val first = repository.observeGroups().first().single()
        val command = FinishGroupCommand(UUID.randomUUID(), first.group.id)
        val completed = repository.finishGroup(command)
        val repeated = repository.finishGroup(command)
        assertTrue(repeated.wasRepeated)
        assertEquals(completed.nextGroupId, repeated.nextGroupId)
        val groups = repository.observeGroups().first()
        assertEquals(2, groups.size)
        assertEquals(1, groups.count { it.group.state == GroupState.FORMING })
        assertEquals(GroupState.CALCULATING, groups.first { it.group.id == first.group.id }.group.state)
    }

    @Test fun incompletePurchaseSurvivesAsIncomplete() = runTest {
        val id = repository.saveOrder(orderCommand().copy(
            lines = listOf(OrderLineDraft(
                productName = "Без закупки", quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal("100"),
                zeroSalePriceConfirmed = true,
            )),
        ))
        val details = repository.observeOrder(id).first()!!
        assertFalse(details.order.costsConfirmed)
        assertEquals("INCOMPLETE", details.order.calculationState.name)
        assertEquals(null, details.lines.single().purchaseUnitPrice)
    }

    @Test fun paidOrderWithTwoLinesCanBeReadBack() = runTest {
        val command = orderCommand().copy(
            receivedPayment = BigDecimal("150"),
            lines = listOf(
                OrderLineDraft(productName = "Первый", article = "A-1", quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal("100"), zeroSalePriceConfirmed = true),
                OrderLineDraft(productName = "Второй", article = "A-2", quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal("50"), zeroSalePriceConfirmed = true),
            ),
        )
        val id = repository.saveOrder(command)
        val details = repository.observeOrder(id).first()!!
        assertEquals(2, details.lines.size)
        assertEquals(15000L, details.order.receivedPayment.minorUnits)
        assertNotEquals(details.lines[0].id, details.lines[1].id)
    }

    @Test fun closedGroupRejectsHiddenSaleChanges() = runTest {
        val id = repository.saveOrder(orderCommand())
        val group = repository.observeGroups().first().single().group
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), group.id))
        val details = repository.observeOrder(id).first()!!
        val line = details.lines.single()
        val changed = orderCommand().copy(
            commandId = UUID.randomUUID(), orderId = id, receivedPayment = BigDecimal("120"),
            lines = listOf(OrderLineDraft(
                id = line.id, productId = line.productId, productName = line.productNameSnapshot,
                article = line.articleSnapshot, quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal("120"),
                zeroSalePriceConfirmed = true, purchaseUnitPrice = BigDecimal("50"),
                purchaseCurrency = ru.liftzip.orders.domain.model.Currency.RUB, purchaseRate = BigDecimal.ONE,
            )),
        )
        var rejected = false
        try { repository.saveOrder(changed) } catch (_: ru.liftzip.orders.domain.model.DomainValidationException) { rejected = true }
        assertTrue(rejected)
        assertEquals(10000L, repository.observeOrder(id).first()!!.order.receivedPayment.minorUnits)
    }

    @Test fun formingGroupAllowsSaleCorrection() = runTest {
        val command = orderCommand()
        val id = repository.saveOrder(command)
        val line = repository.observeOrder(id).first()!!.lines.single()
        repository.saveOrder(command.copy(
            commandId = UUID.randomUUID(), orderId = id, receivedPayment = BigDecimal("120"),
            lines = listOf(command.lines.single().copy(id = line.id, productId = line.productId, saleUnitPrice = BigDecimal("120"))),
        ))
        assertEquals(12000L, repository.observeOrder(id).first()!!.order.receivedPayment.minorUnits)
    }

    @Test fun costChangesKeepAmountsInAudit() = runTest {
        val command = orderCommand().copy(costs = listOf(ru.liftzip.orders.domain.model.CostDraft(
            kind = ru.liftzip.orders.domain.model.CostKind.DELIVERY, amount = BigDecimal("350"),
            currency = ru.liftzip.orders.domain.model.Currency.RUB, rate = BigDecimal.ONE,
            isConfirmed = true, date = LocalDate.parse("2026-11-11"), comment = "Тест",
        )))
        val id = repository.saveOrder(command)
        val details = repository.observeOrder(id).first()!!
        val line = details.lines.single()
        repository.saveOrder(command.copy(
            commandId = UUID.randomUUID(), orderId = id,
            lines = listOf(command.lines.single().copy(id = line.id, productId = line.productId)),
            costs = listOf(command.costs.single().copy(id = details.costs.single().id, amount = BigDecimal("450"))),
        ))
        val audit = repository.observeOrder(id).first()!!.history.first { it.changeType == "UPDATED" }
        assertTrue(audit.beforeJson.orEmpty().contains("350"))
        assertTrue(audit.afterJson.orEmpty().contains("450"))
    }

    @Test fun deletingOrderCleansDependentsKeepsAuditAndRecomputesGroup() = runTest {
        val first = repository.saveOrder(orderCommand())
        val second = repository.saveOrder(orderCommand().copy(commandId = UUID.randomUUID(),
            costs = listOf(ru.liftzip.orders.domain.model.CostDraft(kind = ru.liftzip.orders.domain.model.CostKind.OTHER,
                amount = BigDecimal("7"), date = LocalDate.parse("2026-11-11")))))
        val groupId = repository.observeOrder(first).first()!!.group.id
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), groupId))
        ru.liftzip.orders.domain.model.OrderAction.entries.forEach { repository.setOrderAction(first, it, true) }
        val removed = repository.observeOrder(second).first()!!
        repository.deleteOrder(second)
        repository.deleteOrder(second)
        assertEquals(null, repository.observeOrder(second).first())
        val group = repository.observeGroup(groupId).first()!!
        assertEquals(1, group.orders.size)
        assertEquals(GroupState.COMPLETED, group.summary.group.state)
        assertTrue(database.backupDao().allPriceRecords().none { it.orderId == second.toString() })
        assertTrue(database.backupDao().allCosts().none { it.ownerId == second.toString() || it.ownerId in removed.lines.map { line -> line.id.toString() } })
        val audit = database.ordersDao().getOrderHistory(second.toString())
        assertEquals(1, audit.count { it.changeType == "DELETED" })
        assertTrue(audit.single { it.changeType == "DELETED" }.beforeJson!!.contains("Тест"))
        val next = repository.saveOrder(orderCommand())
        assertEquals(3L, repository.observeOrder(next).first()!!.order.number)
        repository.deleteOrder(first)
        assertEquals(GroupState.CALCULATING, repository.observeGroup(groupId).first()!!.summary.group.state)
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun removingPositionFromFormedOrderKeepsRemainingPriceAndCosts() = runTest {
        val draft = orderCommand().copy(lines = listOf(
            OrderLineDraft(productName = "Первый", quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal("100"), zeroSalePriceConfirmed = true,
                costs = listOf(ru.liftzip.orders.domain.model.CostDraft(kind = ru.liftzip.orders.domain.model.CostKind.OTHER,
                    amount = BigDecimal("5"), date = LocalDate.parse("2026-11-11")))),
            OrderLineDraft(productName = "Второй", quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal("50"), zeroSalePriceConfirmed = true),
        ))
        val id = repository.saveOrder(draft)
        val before = repository.observeOrder(id).first()!!
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), before.group.id))
        val remaining = before.lines.single { it.productNameSnapshot == "Второй" }
        repository.saveOrder(draft.copy(commandId = UUID.randomUUID(), orderId = id,
            lines = listOf(draft.lines[1].copy(id = remaining.id, productId = remaining.productId))))
        val after = repository.observeOrder(id).first()!!
        assertEquals(listOf(remaining.id), after.lines.map { it.id })
        assertEquals(5000L, repository.observeGroup(before.group.id).first()!!.orders.single().calculation.priceForDenis.minorUnits)
        assertEquals(null, repository.observeGroup(before.group.id).first()!!.summary.income)
        assertTrue(after.costs.isEmpty())
        assertEquals(1, database.backupDao().allPriceRecords().count { it.orderId == id.toString() })
        assertTrue(after.history.any { it.changeType == "UPDATED" && it.beforeJson!!.contains("Первый") })
    }

    @Test fun deletingOrderWithDeliveryFinancialRecordsIsRejectedAtomically() = runTest {
        val id = repository.saveOrder(orderCommand())
        database.deliveryDao().insertPayment(ru.liftzip.orders.data.database.DeliveryPaymentEntity(
            UUID.randomUUID().toString(), id.toString(), "CLIENT", 100L, "2026-11-11", "Оплата", null))
        try { repository.deleteOrder(id); throw AssertionError("Financial record must survive") }
        catch (_: ru.liftzip.orders.domain.model.DomainValidationException) { }
        assertTrue(repository.observeOrder(id).first() != null)
        assertEquals(1, database.deliveryDao().payments(id.toString()).size)
        assertTrue(database.ordersDao().getOrderHistory(id.toString()).none { it.changeType == "DELETED" })
    }

    @Test fun savedOrderAndGroupUseApprovedFormulaWithoutDeliveryInK() = runTest {
        val command = orderCommand().copy(manualDeliveryPrice = BigDecimal("200"),
            lines = listOf(orderCommand().lines.single().copy(saleUnitPrice = BigDecimal("13000"))),
            costs = listOf(ru.liftzip.orders.domain.model.CostDraft(kind = ru.liftzip.orders.domain.model.CostKind.PURCHASE,
                amount = BigDecimal("8000"), date = LocalDate.parse("2026-11-11"))))
        val id = repository.saveOrder(command)
        val saved = repository.observeOrder(id).first()!!
        val group = repository.observeGroup(saved.group.id).first()!!
        assertEquals(1280000L, group.orders.single().calculation.income!!.minorUnits)
        assertEquals(800000L, group.orders.single().calculation.purchaseCost!!.minorUnits)
        assertEquals(480000L, group.orders.single().calculation.profit!!.minorUnits)
        assertEquals(1280000L, group.summary.income!!.minorUnits)
        assertEquals(800000L, group.summary.purchaseCost!!.minorUnits)
        assertEquals(480000L, group.summary.profit!!.minorUnits)
        val line = saved.lines.single()
        repository.saveOrder(command.copy(commandId = UUID.randomUUID(), orderId = id, costs = emptyList(),
            lines = listOf(command.lines.single().copy(id = line.id, productId = line.productId))))
        val incomplete = repository.observeGroup(saved.group.id).first()!!
        assertEquals(1280000L, incomplete.summary.income!!.minorUnits)
        assertEquals(null, incomplete.summary.purchaseCost)
        assertEquals(null, incomplete.summary.profit)
    }

    private fun orderCommand() = SaveOrderCommand(
        commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = "Тест",
        receivedPayment = BigDecimal("100"), costsConfirmed = false,
        lines = listOf(OrderLineDraft(
            productName = "Товар", article = "TEST-1", quantity = BigDecimal.ONE,
            saleUnitPrice = BigDecimal("100"), zeroSalePriceConfirmed = true,
            purchaseUnitPrice = BigDecimal("50"), purchaseCurrency = ru.liftzip.orders.domain.model.Currency.RUB,
            purchaseRate = BigDecimal.ONE,
        )),
        costs = emptyList(),
    )
}
