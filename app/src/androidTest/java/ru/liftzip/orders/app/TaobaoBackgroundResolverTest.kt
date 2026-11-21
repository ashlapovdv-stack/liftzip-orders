package ru.liftzip.orders.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.orders.resolveTaobaoInBackground

@RunWith(AndroidJUnit4::class)
class TaobaoBackgroundResolverTest {
    @Test fun followsJavaScriptRedirectWithoutAttachingAWebView() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val canonical = "https://item.taobao.com/item.htm?id=123456789"
        val resolved = resolveTaobaoInBackground(context, "https://e.tb.cn/fixture") { view, initial ->
            assertFalse(view.isAttachedToWindow)
            view.loadDataWithBaseURL(
                initial,
                "<script>location.replace('$canonical')</script>",
                "text/html",
                "utf-8",
                null,
            )
        }
        assertEquals(canonical, resolved)
    }
}
