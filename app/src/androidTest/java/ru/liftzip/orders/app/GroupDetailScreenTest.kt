package ru.liftzip.orders.app

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.groups.GroupDetailScreen
import ru.liftzip.orders.app.ui.OrdersTheme
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository
import ru.liftzip.orders.domain.model.*
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GroupDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: OrdersDatabase
    private lateinit var repository: RoomOrdersRepository
    private val longTitle = "Заказ 11.11 тяговая цепь, лифтмонтаж ДВ 2"
    private lateinit var orderIds: List<UUID>
    private lateinit var orderId: UUID
    private lateinit var groupId: UUID
    private var openedOrder: UUID? = null

    @Before fun setUp(): Unit = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrdersDatabase::class.java).build()
        repository = RoomOrdersRepository(database)
        repository.initialize()
        orderIds = listOf(
            Triple(longTitle, "240000", "120000"),
            Triple("Заказ 11.11 электроника", "180000", "90000"),
            Triple("Заказ 12.11 комплектующие", "120000", "60000"),
        ).map { (title, sale, purchase) ->
            repository.saveOrder(SaveOrderCommand(
                commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = title,
                receivedPayment = BigDecimal.ZERO, costsConfirmed = false,
                lines = listOf(OrderLineDraft(productName = "Тестовая позиция", quantity = BigDecimal.ONE,
                    saleUnitPrice = BigDecimal(sale), zeroSalePriceConfirmed = true)),
                costs = listOf(CostDraft(kind = CostKind.PURCHASE, amount = BigDecimal(purchase),
                    date = LocalDate.parse("2026-11-11"))), manualDeliveryPrice = BigDecimal.ZERO,
            ))
        }
        orderId = orderIds.first()
        groupId = repository.observeOrder(orderId).first()!!.group.id
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), groupId))
    }

    @After fun tearDown() { database.close() }

    @Test fun compactTableShowsAllAmountsAndActionsCompleteAndReopenGroup() {
        showScreen(1f)
        compose.onNodeWithText("Полученная оплата: 0 ₽").assertDoesNotExist()
        compose.onNodeWithText("Статус «На расчёте» не означает утверждение расчёта или подтверждение выкупа").assertDoesNotExist()
        compose.onNodeWithText("Открыть ›").assertDoesNotExist()
        compose.onNodeWithText("Суммы в ₽ · Д — доход · К — расход · ∆ — прибыль").assertDoesNotExist()
        assertEquals("540 000 ₽", summaryText("Д"))
        assertEquals("270 000 ₽", summaryText("К"))
        assertEquals("270 000 ₽", summaryText("∆"))
        for ((title, word) in listOf(longTitle to "лифтмонтаж", "Заказ 11.11 электроника" to "электроника", "Заказ 12.11 комплектующие" to "комплектующие")) {
            val layout = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            val text = layout.single()
            org.junit.Assert.assertTrue((0 until text.lineCount).any {
                title.substring(text.getLineStart(it), text.getLineEnd(it)).contains(word)
            })
        }
        capture("group-table.png")
        orderIds.forEachIndexed { index, id ->
            for ((action, description) in listOf(
                OrderAction.WITHDRAWN to "Получены деньги от Дениса",
                OrderAction.TRANSFERRED to "Деньги за товар переданы посреднику",
                OrderAction.RECEIVED to "Товар получен",
            )) {
                clickAction(description, index + 1)
                compose.waitUntil(10_000) {
                    runBlocking {
                        val p = repository.observeOrder(id).first()!!.order.fulfillment
                        when (action) {
                            OrderAction.WITHDRAWN -> p.withdrawn
                            OrderAction.TRANSFERRED -> p.transferred
                            OrderAction.RECEIVED -> p.received
                        }
                    }
                }
                compose.waitForIdle()
            }
            if (index == 0) compose.onNodeWithText("1 / 3 выполнено").assertExists()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Выполнена").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Выполнена").performScrollTo()
        capture("group-completed.png")
        clickAction("Товар получен")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("На расчёте").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(longTitle).performScrollTo().performClick()
        assertEquals(orderId, openedOrder)
        compose.onNodeWithText("Сохранить расходы группы").assertDoesNotExist()
        compose.onNodeWithText("Расходы группы").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить расходы группы").assertExists()
        compose.onNodeWithText("Расходы группы").performClick()
        compose.onNodeWithText("Сохранить расходы группы").assertDoesNotExist()
        compose.onNodeWithText("Добавить расход").performScrollTo().performClick()
        compose.onNodeWithText("Сумма расхода").assertExists()
    }

    @Test fun largerFontKeepsTableAndActionsWithinScreen() {
        showScreen(1.3f, 320f)
        assertLongTitleAndValuesFit()
        capture("group-large-font.png")
    }

    @Test fun phoneWidthKeepsFiveColumnsAndFullLongTitle() {
        showScreen(1.3f, 393f)
        compose.onNodeWithText("Действия").assertIsDisplayed()
        assertLongTitleAndValuesFit()
        capture("group-inline-large-font.png")
    }

    private fun assertLongTitleAndValuesFit() {
        val root = compose.onNodeWithTag("group-screen").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText(longTitle).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        org.junit.Assert.assertTrue(layouts.single().lineCount > 1)
        org.junit.Assert.assertFalse((0 until layouts.single().lineCount).any { layouts.single().isLineEllipsized(it) })
        for (description in listOf("Доход: 240 000", "Расход: 120 000", "Прибыль: 120 000")) {
            val exact = description.replace(' ', '\u00a0').replace(":\u00a0", ": ")
            val node = compose.onNodeWithContentDescription(exact).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(bounds.right <= root.right + 1 && bounds.left >= root.left)
            org.junit.Assert.assertTrue(title.fetchSemanticsNode().boundsInRoot.right <= bounds.left + 1)
            val values = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
            assertEquals(1, values.single().lineCount)
        }
    }

    private fun summaryText(label: String): String = compose.onNodeWithTag("group-total-$label")
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        .replace('\u00a0', ' ').replace('\u202f', ' ')

    private fun showScreen(fontScale: Float, width: Float = 393f) {
        compose.setContent {
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewportDensity = density.density * minOf(maxWidth.value / width, 1f)
                CompositionLocalProvider(LocalDensity provides Density(viewportDensity, fontScale)) {
                    OrdersTheme {
                        Box(Modifier.width(width.dp).testTag("group-screen")) {
                            GroupDetailScreen(repository, groupId, onBack = {}, onOpenOrder = { openedOrder = it })
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(longTitle).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val image = compose.onNodeWithTag("group-screen").captureToImage().asAndroidBitmap()
        // AGP uninstalls the target after connected tests. Test images must outlive it.
        val output = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/OrdersTest")
            }
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            checkNotNull(context.contentResolver.openOutputStream(uri))
        } else File(context.filesDir, name).outputStream()
        output.use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun clickAction(description: String, orderNumber: Int = 1) {
        val node = compose.onNodeWithContentDescription("Заказ №$orderNumber: $description")
        compose.waitUntil(10_000) { !node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        node.performScrollTo().performClick()
    }
}
