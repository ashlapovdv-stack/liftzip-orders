package ru.liftzip.orders.domain

import org.junit.Assert.*
import org.junit.Test
import ru.liftzip.orders.domain.model.OrderStage
import ru.liftzip.orders.domain.model.OrderAction
import ru.liftzip.orders.domain.model.OrderFulfillment

class OrderFulfillmentTest {
    @Test fun `only all three independent actions complete an order`() {
        for (mask in 0..7) {
            val progress = OrderFulfillment(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            assertEquals(mask == 7, progress.isComplete)
        }
    }
    @Test fun `stage follows the earliest unfinished action for every combination`() {
        val cases = listOf(
            OrderFulfillment(false, false, false) to OrderStage.ACCEPTED,
            OrderFulfillment(false, false, true) to OrderStage.ACCEPTED,
            OrderFulfillment(false, true, false) to OrderStage.ACCEPTED,
            OrderFulfillment(false, true, true) to OrderStage.ACCEPTED,
            OrderFulfillment(true, false, false) to OrderStage.AWAITING_PAYMENT,
            OrderFulfillment(true, false, true) to OrderStage.AWAITING_PAYMENT,
            OrderFulfillment(true, true, false) to OrderStage.IN_DELIVERY,
            OrderFulfillment(true, true, true) to OrderStage.COMPLETED,
        )
        cases.forEach { (flags, expected) -> assertEquals(flags.toString(), expected, flags.stage) }
    }

    @Test fun `changing one action preserves the other actions`() {
        val complete = OrderFulfillment(true, true, true)
        assertEquals(OrderFulfillment(false, true, true), complete.withAction(OrderAction.WITHDRAWN, false))
        assertEquals(OrderFulfillment(true, false, true), complete.withAction(OrderAction.TRANSFERRED, false))
        assertEquals(OrderFulfillment(true, true, false), complete.withAction(OrderAction.RECEIVED, false))
        assertEquals(complete, complete.withAction(OrderAction.RECEIVED, true))
    }
}
