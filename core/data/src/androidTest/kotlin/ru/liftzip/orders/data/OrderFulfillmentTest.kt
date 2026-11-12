package ru.liftzip.orders.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository
import ru.liftzip.orders.domain.model.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrderFulfillmentTest {
    private lateinit var database: OrdersDatabase
    private lateinit var repository: RoomOrdersRepository

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrdersDatabase::class.java).build()
        repository = RoomOrdersRepository(database)
        repository.initialize()
    }
    @After fun tearDown() { database.close() }

    @Test fun allActionsOnEveryOrderCompleteGroupAndUncheckingReopensIt() = runBlocking {
        val first = repository.saveOrder(command())
        val second = repository.saveOrder(command())
        val group = repository.observeOrder(first).first()!!.group
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), group.id))
        for (action in OrderAction.entries) repository.setOrderAction(first, action, true)
        assertEquals(GroupState.CALCULATING, repository.observeOrder(first).first()!!.group.state)
        repository.setOrderAction(second, OrderAction.RECEIVED, true)
        repository.setOrderAction(second, OrderAction.TRANSFERRED, true)
        assertEquals(GroupState.CALCULATING, repository.observeOrder(second).first()!!.group.state)
        repository.setOrderAction(second, OrderAction.WITHDRAWN, true)
        assertEquals(GroupState.COMPLETED, repository.observeOrder(first).first()!!.group.state)
        assertEquals(1, repository.observeGroups().first().count { it.group.state == GroupState.FORMING })
        repository.setOrderAction(first, OrderAction.TRANSFERRED, false)
        val reopened = repository.observeOrder(first).first()!!
        assertEquals(GroupState.CALCULATING, reopened.group.state)
        assertTrue(reopened.order.fulfillment.withdrawn)
        assertFalse(reopened.order.fulfillment.transferred)
        assertTrue(reopened.order.fulfillment.received)
    }

    @Test fun repeatedCheckIsIdempotentAndEditorSavePreservesFlagsAndMoney() = runBlocking {
        val original = command().copy(orderRate = BigDecimal("13.25"))
        val id = repository.saveOrder(original)
        val group = repository.observeOrder(id).first()!!.group
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), group.id))
        repository.setOrderAction(id, OrderAction.WITHDRAWN, true)
        val checked = repository.observeOrder(id).first()!!
        repository.setOrderAction(id, OrderAction.WITHDRAWN, true)
        val repeated = repository.observeOrder(id).first()!!
        assertEquals(checked.order.revision, repeated.order.revision)
        assertEquals(checked.history.size, repeated.history.size)
        val line = repeated.lines.single()
        repository.saveOrder(original.copy(
            commandId = UUID.randomUUID(), orderId = id, comment = "Уточнение",
            lines = listOf(original.lines.single().copy(id = line.id, productId = line.productId)),
        ))
        val saved = repository.observeOrder(id).first()!!
        assertTrue(saved.order.fulfillment.withdrawn)
        assertFalse(saved.order.fulfillment.transferred)
        assertEquals(Money(10000), saved.order.receivedPayment)
        assertEquals(checked.lines, saved.lines.map { it.copy(updatedAt = checked.lines.single().updatedAt, revision = checked.lines.single().revision) })
        assertEquals(0, saved.order.orderRate!!.compareTo(BigDecimal("13.25")))
    }

    @Test fun formingGroupDoesNotAcceptFulfillmentMarks() = runBlocking {
        val id = repository.saveOrder(command())
        var rejected = false
        try { repository.setOrderAction(id, OrderAction.WITHDRAWN, true) }
        catch (_: DomainValidationException) { rejected = true }
        assertTrue(rejected)
        assertFalse(repository.observeOrder(id).first()!!.order.fulfillment.isComplete)
    }

    private fun command() = SaveOrderCommand(
        commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = "Тест",
        receivedPayment = BigDecimal("100"), costsConfirmed = false,
        lines = listOf(OrderLineDraft(productName = "Товар", quantity = BigDecimal.ONE,
            saleUnitPrice = BigDecimal("100"), zeroSalePriceConfirmed = true)), costs = emptyList(),
    )
}
