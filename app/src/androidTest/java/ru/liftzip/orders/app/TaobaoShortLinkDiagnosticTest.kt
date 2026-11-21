package ru.liftzip.orders.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.orders.TaobaoLinkResolver

/**
 * Opt-in network diagnostic of the actual production resolver.
 * Not part of the default instrumented test suite.
 */
@RunWith(AndroidJUnit4::class)
class TaobaoShortLinkDiagnosticTest {
    @Test fun shortShareLinkResolvesToKnownItemId() = runBlocking {
        assumeTrue(
            "Manual network diagnostic only",
            InstrumentationRegistry.getArguments().getString("taobaoDiagnostic") == "true",
        )
        assertEquals(
            "https://item.taobao.com/item.htm?id=45184215152",
            TaobaoLinkResolver.resolve("https://e.tb.cn/h.jPf9texwTwwAHxe?tk=ShmK6WCstGY"),
        )
    }
}
