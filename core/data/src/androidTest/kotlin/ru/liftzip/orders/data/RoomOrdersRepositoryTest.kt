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
        assertEquals(BigDecimal("55"), history.latestPurchase!!.record.price)
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
