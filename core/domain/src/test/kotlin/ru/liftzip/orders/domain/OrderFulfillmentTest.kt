package ru.liftzip.orders.domain

import org.junit.Assert.*
import org.junit.Test
import ru.liftzip.orders.domain.model.OrderAction
import ru.liftzip.orders.domain.model.OrderFulfillment

class OrderFulfillmentTest {
    @Test fun `only all three independent actions complete an order`() {
        for (mask in 0..7) {
            val progress = OrderFulfillment(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            assertEquals(mask == 7, progress.isComplete)
        }
    }
    @Test fun `changing one action preserves the other actions`() {
        val complete = OrderFulfillment(true, true, true)
        assertEquals(OrderFulfillment(false, true, true), complete.withAction(OrderAction.WITHDRAWN, false))
        assertEquals(OrderFulfillment(true, false, true), complete.withAction(OrderAction.TRANSFERRED, false))
        assertEquals(OrderFulfillment(true, true, false), complete.withAction(OrderAction.RECEIVED, false))
        assertEquals(complete, complete.withAction(OrderAction.RECEIVED, true))
    }
}
