package ru.liftzip.orders.app.feature.orders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaobaoLinksTest {
    @Test fun extractsShortUrlFromChineseShareText() {
        val share = "【淘宝】https://e.tb.cn/h.jPf9texwTwwAHxe?tk=ShmK6WCstGY CZ356 「商品」"
        assertEquals("https://e.tb.cn/h.jPf9texwTwwAHxe?tk=ShmK6WCstGY",
            TaobaoLinks.sharedUrl(share)?.toString())
    }

    @Test fun convertsDirectItemUrlToPermanentUrl() {
        val uri = TaobaoLinks.sharedUrl("https://item.taobao.com/item.htm?id=45184215152&foo=bar")!!
        assertEquals("https://item.taobao.com/item.htm?id=45184215152", TaobaoLinks.canonicalUrl(uri))
    }

    @Test fun doesNotGuessItemIdFromShortLink() {
        val uri = TaobaoLinks.sharedUrl("https://e.tb.cn/h.jPf9texwTwwAHxe?tk=ShmK6WCstGY")!!
        assertNull(TaobaoLinks.canonicalUrl(uri))
    }

    @Test fun rejectsUntrustedHostsAndInvalidIds() {
        assertNull(TaobaoLinks.sharedUrl("https://taobao.com.evil.example/item.htm?id=45184215152"))
        assertNull(TaobaoLinks.sharedUrl("http://item.taobao.com/item.htm?id=45184215152"))
        assertNull(TaobaoLinks.canonicalUrl(TaobaoLinks.sharedUrl("https://item.taobao.com/item.htm?id=abc")!!))
    }
}
