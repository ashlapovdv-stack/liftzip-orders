package ru.liftzip.orders.domain

import org.junit.Assert.*
import org.junit.Test
import ru.liftzip.orders.domain.model.OrderStage
import ru.liftzip.orders.domain.model.OrderAction
import ru.liftzip.orders.domain.model.OrderFulfillment
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.groupProgressState

class OrderFulfillmentTest {
    private val awaitingPayment = OrderFulfillment(withdrawn = true, transferred = true)
    private val delivery = awaitingPayment.copy(paid = true)
    private val complete = delivery.copy(received = true)

    @Test fun groupFollowsTheEarliestIncompleteStageAcrossOrders() {
        assertEquals(GroupState.CALCULATING, groupProgressState(emptyList()))
        assertEquals(GroupState.CALCULATING, groupProgressState(listOf(delivery, complete.copy(transferred = false))))
        assertEquals(GroupState.CALCULATING, groupProgressState(listOf(delivery, complete.copy(withdrawn = false))))
        assertEquals(GroupState.AWAITING_PAYMENT, groupProgressState(listOf(awaitingPayment, delivery)))
        assertEquals(GroupState.AWAITING_PAYMENT, groupProgressState(listOf(awaitingPayment.copy(received = true), complete)))
        assertEquals(GroupState.IN_DELIVERY, groupProgressState(listOf(delivery, complete)))
        assertEquals(GroupState.COMPLETED, groupProgressState(listOf(complete, complete)))
    }

    @Test fun statusAndCompletionFollowTheFirstUncheckedAction() {
        val cases = mapOf(
            OrderStage.ACCEPTED to (0..7),
            OrderStage.AWAITING_TRANSFER to (8..11),
            OrderStage.AWAITING_PAYMENT to (12..13),
            OrderStage.IN_DELIVERY to (14..14),
            OrderStage.COMPLETED to (15..15),
        )
        for ((expected, combinations) in cases) for (mask in combinations) {
            val progress = OrderFulfillment(
                withdrawn = mask and 8 != 0, transferred = mask and 4 != 0,
                paid = mask and 2 != 0, received = mask and 1 != 0,
            )
            assertEquals(progress.toString(), expected, progress.stage)
            assertEquals(mask == 15, progress.isComplete)
        }
    }

    @Test fun changingOneActionPreservesTheOtherActions() {
        assertEquals(complete.copy(withdrawn = false), complete.withAction(OrderAction.WITHDRAWN, false))
        assertEquals(complete.copy(transferred = false), complete.withAction(OrderAction.TRANSFERRED, false))
        assertEquals(complete.copy(paid = false), complete.withAction(OrderAction.PAID, false))
        assertEquals(complete.copy(received = false), complete.withAction(OrderAction.RECEIVED, false))
        assertEquals(complete, complete.withAction(OrderAction.PAID, true))
    }
}
