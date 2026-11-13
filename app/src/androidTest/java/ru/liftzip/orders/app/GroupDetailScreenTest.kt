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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.prices.PricesScreen
import ru.liftzip.orders.app.feature.prices.ProductDetailScreen
import ru.liftzip.orders.app.feature.orders.OrdersScreen
import ru.liftzip.orders.app.feature.orders.OrderEditorScreen
import ru.liftzip.orders.app.feature.groups.GroupsScreen
import ru.liftzip.orders.app.feature.groups.GroupDetailScreen
import ru.liftzip.orders.app.ui.MainScreenScaffold
import ru.liftzip.orders.app.ui.OrdersTheme
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository
import ru.liftzip.orders.domain.model.*
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GroupDetailScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: OrdersDatabase
    private lateinit var repository: RoomOrdersRepository
    private val longTitle = "Заказ 11.11 тяговая цепь, лифтмонтаж ДВ 2"
    private lateinit var orderIds: List<UUID>
    private lateinit var orderId: UUID
    private lateinit var groupId: UUID
    private var openedOrder: UUID? = null
    private var showGroups by mutableStateOf(false)
    private var showOrder by mutableStateOf(false)
    private var showOrders by mutableStateOf(false)
    private var openedGroup: UUID? = null
    private var previewTab by mutableStateOf(0)
    private var newOrderPreview = false
    private var showPrices by mutableStateOf(false)
    private var openedProduct by mutableStateOf<UUID?>(null)

    @Before fun setUp(): Unit = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrdersDatabase::class.java).build()
        repository = RoomOrdersRepository(database)
        repository.initialize()
        orderIds = listOf(
            Triple(longTitle, "240000", "120000"),
            Triple("Заказ 11.11 электроника", "180000", "90000"),
            Triple("Заказ 12.11 комплектующие", "120000", "60000"),
        ).mapIndexed { index, (title, sale, purchase) ->
            repository.saveOrder(SaveOrderCommand(
                commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = title,
                receivedPayment = BigDecimal.ZERO, costsConfirmed = false,
                lines = listOf(OrderLineDraft(productName = "Тестовая позиция", quantity = BigDecimal.ONE,
                    saleUnitPrice = BigDecimal(sale), zeroSalePriceConfirmed = true,
                    purchaseUnitPrice = if (index == 2) BigDecimal("800") else null,
                    purchaseCurrency = if (index == 2) Currency.CNY else null, purchaseRate = if (index == 2) BigDecimal("10") else null,
                    deliveryWeight = if (index == 2) BigDecimal("0.2") else null,
                    deliveryTariff = if (index == 2) BigDecimal("55") else null,
                    deliveryPlanDate = if (index == 2) LocalDate.parse("2026-11-11") else null)),
                orderRate = BigDecimal("10"),
                costs = listOf(CostDraft(kind = CostKind.PURCHASE, amount = BigDecimal(purchase),
                    date = LocalDate.parse("2026-11-11"))), manualDeliveryPrice = BigDecimal.ZERO,
            ))
        }
        orderId = orderIds.first()
        groupId = repository.observeOrder(orderId).first()!!.group.id
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), groupId))
    }

    @After fun tearDown() {
        // Activity destruction cancels ViewModels; await Room observer cleanup too.
        val jobs = compose.runOnIdle {
            val store = compose.activity.viewModelStore
            store.keys().mapNotNull { store[it]?.viewModelScope?.coroutineContext?.get(Job) }
        }
        compose.activityRule.scenario.close()
        runBlocking { jobs.joinAll() }
        database.close()
    }

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
        assertFiveColumnActions()
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
        // Reuse this fixture for a visual preview of all three lifecycle colors.
        runBlocking {
            repository.setOrderAction(orderId, OrderAction.RECEIVED, true)
            val secondOrder = repository.saveOrder(SaveOrderCommand(
                commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = "Пример заказа",
                receivedPayment = BigDecimal.ZERO, costsConfirmed = false,
                lines = listOf(OrderLineDraft(productName = "Позиция", quantity = BigDecimal.ONE,
                    saleUnitPrice = BigDecimal("1220000"), zeroSalePriceConfirmed = true)),
                costs = listOf(CostDraft(kind = CostKind.PURCHASE, amount = BigDecimal("8500"),
                    date = LocalDate.parse("2026-11-11"))), manualDeliveryPrice = BigDecimal.ZERO,
            ))
            val secondGroup = repository.observeOrder(secondOrder).first()!!.group.id
            repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), secondGroup))
        }
        compose.runOnIdle { showGroups = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Группа №3").fetchSemanticsNodes().isNotEmpty() }
        capture("groups-list.png")
    }

    @Test fun orderEditorUsesTheSameFulfillmentAsGroup() {
        showOrder = true
        showScreen(1f)
        actionNode(OrderAction.WITHDRAWN).assertIsOff()
        clickAction("Получены деньги от Дениса")
        waitAction(OrderAction.WITHDRAWN, true)
        val draft = "Комментарий не должен исчезнуть при обновлении отметок"
        scrollToEditorField("Комментарий")
        compose.onNodeWithText("Комментарий").performTextReplacement(draft)
        runBlocking {
            repository.setOrderAction(orderId, OrderAction.TRANSFERRED, true)
            orderIds.drop(1).forEach { id -> OrderAction.entries.forEach { repository.setOrderAction(id, it, true) } }
            repository.setOrderAction(orderId, OrderAction.RECEIVED, true)
        }
        scrollToEditorField("order-actions")
        waitAction(OrderAction.RECEIVED, true)
        actionNode(OrderAction.TRANSFERRED).assertIsOn()
        scrollToEditorField("Комментарий")
        compose.onNodeWithText(draft).assertExists()
        scrollToEditorField("order-actions")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Выполнена").fetchSemanticsNodes().isNotEmpty() }
        actionNode(OrderAction.WITHDRAWN).performScrollTo()
        capture("order-editor-flags.png")
        clickAction("Получены деньги от Дениса")
        waitAction(OrderAction.WITHDRAWN, false)
        compose.onNodeWithText("Сохранить", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Группа №1 →").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(draft, runBlocking { repository.observeOrder(orderId).first()!!.order.comment })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(longTitle))
        actionNode(OrderAction.WITHDRAWN).assertIsOff()
        actionNode(OrderAction.TRANSFERRED).assertIsOn()
        actionNode(OrderAction.RECEIVED).assertIsOn()
        org.junit.Assert.assertFalse(actionNode(OrderAction.WITHDRAWN).fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        capture("orders-list.png")
        compose.onAllNodesWithText("Группа №1 →").onFirst().performScrollTo().performClick()
        assertEquals(groupId, openedGroup)
        compose.onNodeWithText("Группа №1").assertExists()
        actionNode(OrderAction.WITHDRAWN).assertIsOff()
        actionNode(OrderAction.TRANSFERRED).assertIsOn()
        clickAction("Получены деньги от Дениса")
        waitAction(OrderAction.WITHDRAWN, true)
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText(longTitle).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("order-actions").fetchSemanticsNodes().isNotEmpty() }
        OrderAction.entries.forEach { actionNode(it).assertIsOn() }
        scrollToEditorField("Комментарий")
        compose.onNodeWithText(draft).assertExists()
        scrollToEditorField("order-actions")
    }

    @Test fun orderTilesUseTheirOwnNextActionStatus() {
        runBlocking {
            repository.setOrderAction(orderIds[0], OrderAction.WITHDRAWN, true)
            repository.setOrderAction(orderIds[0], OrderAction.TRANSFERRED, true)
            repository.setOrderAction(orderIds[1], OrderAction.WITHDRAWN, true)
        }
        showOrders = true
        showScreen(1.3f, 320f)
        listOf(1 to "Доставка", 2 to "На оплате", 3 to "Принят").forEach { (number, stage) ->
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("order-card-$number"))
            compose.onNodeWithText(stage).assertExists()
            OrderAction.entries.forEach { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("order-$number-$action-caption", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val text = layouts.single()
                assertEquals(1, text.lineCount)
                org.junit.Assert.assertFalse(text.isLineEllipsized(0))
                org.junit.Assert.assertTrue(text.getLineRight(0) <= text.size.width + 1)
            }
        }
        compose.onNodeWithText("Тестовая позиция").assertDoesNotExist()
        val bar = compose.onNodeWithTag("main-bottom-bar").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("group-screen").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(bar.height <= 46f * viewport.width / 320f)
        capture("orders-compact-large-font.png")
        runBlocking { repository.setOrderAction(orderId, OrderAction.RECEIVED, true) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("order-card-1"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Выполнен").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { repository.setOrderAction(orderId, OrderAction.WITHDRAWN, false) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("order-card-1") and hasText("Принят")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("orders-search"))
        compose.onNodeWithTag("orders-search").performTextReplacement("электроника")
        compose.onNodeWithTag("orders-search").performImeAction()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("order-card-3").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("order-card-2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("order-card-2").assertExists()
        compose.onNodeWithTag("order-card-3").assertDoesNotExist()
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.onNodeWithTag("orders-search").performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("order-card-3").fetchSemanticsNodes().isNotEmpty() }
        listOf("Заказы", "Группы", "История цен").forEachIndexed { index, title ->
            compose.onNodeWithTag("main-tab-$index").performClick().assertIsSelected()
            compose.onNodeWithTag("main-title").assertTextEquals(title)
        }
    }

    @Test fun compactOrderEditorSupportsDeletion() {
        orderId = runBlocking {
            val id = repository.saveOrder(SaveOrderCommand(UUID.randomUUID(), date = LocalDate.parse("2026-11-11"),
                counterparty = longTitle, receivedPayment = BigDecimal.ZERO, costsConfirmed = false,
                lines = listOf("Контактор" to "1000", "Датчик" to "500").map { (name, amount) ->
                    OrderLineDraft(productName = name, quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal(amount), zeroSalePriceConfirmed = true)
                }, costs = listOf(CostDraft(kind = CostKind.PURCHASE, amount = BigDecimal("700"), date = LocalDate.parse("2026-11-11"))),
                manualDeliveryPrice = BigDecimal.ZERO, orderRate = BigDecimal("10")))
            repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), repository.observeOrder(id).first()!!.group.id))
            id
        }
        val originalDate = runBlocking { repository.observeOrder(orderId).first()!!.order.date }
        showOrder = true
        showScreen(1f)
        compose.onNodeWithText("Название заказа").assertExists()
        capture("order-editor-compact.png")
        compose.onNodeWithContentDescription("Удалить позицию 1").performScrollTo().performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Товары · 2").assertExists()
        compose.onNodeWithContentDescription("Удалить позицию 1").performClick()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Товары · 1").assertExists()
        scrollToEditorField("Итого")
        capture("order-editor-summary.png")
        compose.onNodeWithText("Сохранить", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("order-card-4").fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { repository.observeOrder(orderId).first()!! }
        assertEquals(1, saved.lines.size)
        assertEquals(originalDate, saved.order.date)
        assertEquals(BigDecimal("10"), saved.order.orderRate)
        compose.onNodeWithTag("order-card-4").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("order-4-WITHDRAWN", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Удалить заказ"))
        val delete = compose.onNodeWithContentDescription("Удалить заказ").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("group-screen").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(delete.fetchSemanticsNode().boundsInRoot.height >= 50f * viewport.width / 393f)
        capture("order-delete-button.png")
        delete.performClick()
        compose.onNodeWithText("Отмена").performClick()
        org.junit.Assert.assertNotNull(runBlocking { repository.observeOrder(orderId).first() })
        compose.onNodeWithContentDescription("Удалить заказ").performClick()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeOrder(orderId).first() == null } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("order-card-4").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun compactPriceHistoryCombinesPricesAndDoesNotNavigateToOrder() {
        showPrices = true
        showScreen(1f)
        compose.onNodeWithText("Тестовая позиция").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Денису за единицу").fetchSemanticsNodes().isNotEmpty() }
        val sourceLine = runBlocking { repository.observeOrder(orderIds.last()).first()!!.lines.single() }
        val tag = "history-line-${sourceLine.id}"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        val card = compose.onNodeWithTag(tag).assertIsDisplayed()
        card.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNode(hasText("120000 ₽") and hasAnyAncestor(hasTestTag(tag))).assertExists()
        compose.onNode(hasText("800 ¥") and hasAnyAncestor(hasTestTag(tag))).assertExists()
        compose.onNode(hasText("Курс заказа: 10 ₽/¥") and hasAnyAncestor(hasTestTag(tag))).assertExists()
        compose.onNodeWithText("Тариф доставки: 55 ¥/кг").assertExists()
        compose.onNodeWithText("Последние известные цены").assertDoesNotExist()
        capture("price-history.png")
        card.performTouchInput { click(center) }
        compose.onNodeWithText("История цен").assertExists()
        compose.onNodeWithText("Название заказа").assertDoesNotExist()
        org.junit.Assert.assertNull(openedOrder)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Вес единицы: не указан"))
        compose.onAllNodesWithText("Вес единицы: не указан").onFirst().assertIsDisplayed()
    }

    @Test fun formingOrderActionsAreBlockedOnBothScreensUntilFormationFinishes() {
        orderId = runBlocking {
            repository.saveOrder(SaveOrderCommand(
                commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = longTitle,
                receivedPayment = BigDecimal.ZERO, costsConfirmed = false,
                lines = listOf(OrderLineDraft(productName = "Позиция", quantity = BigDecimal.ONE,
                    saleUnitPrice = BigDecimal("1000"), zeroSalePriceConfirmed = true)),
                costs = listOf(CostDraft(kind = CostKind.PURCHASE, amount = BigDecimal("500"),
                    date = LocalDate.parse("2026-11-11"))), manualDeliveryPrice = BigDecimal.ZERO,
            ))
        }
        showOrder = true
        showScreen(1f)
        val formingId = runBlocking { repository.observeOrder(orderId).first()!!.group.id }
        OrderAction.entries.forEach { action ->
            val node = compose.onNodeWithTag("order-4-$action", useUnmergedTree = true)
            node.assertIsNotEnabled().assertIsOff()
            compose.onNodeWithTag("order-4-$action-square", useUnmergedTree = true).performTouchInput { click(center) }
            node.assertIsOff()
        }
        assertEquals(OrderFulfillment(), runBlocking { repository.observeOrder(orderId).first()!!.order.fulfillment })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Удалить заказ"))
        compose.onNodeWithContentDescription("Удалить заказ").assertIsEnabled()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Группа №2 →").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Группа №2 →").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Группа №2").fetchSemanticsNodes().isNotEmpty() }
        OrderAction.entries.forEach { action ->
            compose.onNodeWithTag("order-4-$action", useUnmergedTree = true).assertIsNotEnabled().assertIsOff()
            compose.onNodeWithTag("order-4-$action-square", useUnmergedTree = true).performTouchInput { click(center) }
        }
        assertEquals(OrderFulfillment(), runBlocking { repository.observeOrder(orderId).first()!!.order.fulfillment })
        compose.onNodeWithText("Завершить формирование").performScrollTo().performClick()
        compose.onNodeWithText("Завершить", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeGroup(formingId).first()!!.summary.group.state == GroupState.CALCULATING } }
        for (description in listOf("Получены деньги от Дениса", "Деньги за товар переданы посреднику", "Товар получен")) {
            clickAction(description, 4)
        }
        compose.waitUntil(10_000) { runBlocking { repository.observeGroup(formingId).first()!!.summary.group.state == GroupState.COMPLETED } }
        clickAction("Товар получен", 4)
        compose.waitUntil(10_000) { runBlocking { repository.observeGroup(formingId).first()!!.summary.group.state == GroupState.CALCULATING } }
    }

    @Test fun taobaoLinkCanBeCanonicalizedAndSavedFromPositionEditor() {
        showOrder = true
        newOrderPreview = true
        showScreen(1f)
        compose.onNodeWithText("Название заказа").performTextReplacement("Заказ Taobao UI")
        compose.onNodeWithText("Новая позиция").performScrollTo().performClick()
        compose.onNodeWithText("Название товара").performTextReplacement("Товар Taobao UI")
        compose.onNodeWithText("Денису, ₽/шт").performScrollTo().performTextReplacement("100")
        compose.onNodeWithText("Ссылка Taobao").performScrollTo().performTextReplacement(
            "https://item.taobao.com/item.htm?id=45184215152&spm=abc",
        )
        compose.onNodeWithText("Получить постоянную ссылку").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("https://item.taobao.com/item.htm?id=45184215152").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithText("Сохранить", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            runBlocking { repository.observeOrders("", OrdersFilter.ALL, 50).first().any { it.order.counterparty == "Заказ Taobao UI" } }
        }
        val saved = runBlocking {
            val id = repository.observeOrders("", OrdersFilter.ALL, 50).first()
                .single { it.order.counterparty == "Заказ Taobao UI" }.order.id
            repository.observeOrder(id).first()!!
        }
        assertEquals("https://item.taobao.com/item.htm?id=45184215152", saved.lines.single().productUrl)
    }

    @Test fun newOrderCannotSetMarksBeforeItsFirstSave() {
        showOrder = true
        newOrderPreview = true
        showScreen(1f)
        for (action in listOf(OrderAction.WITHDRAWN, OrderAction.TRANSFERRED)) {
            compose.onNodeWithTag("order-new-$action-square", useUnmergedTree = true).performTouchInput { click(center) }
            compose.onNodeWithTag("order-new-$action", useUnmergedTree = true).assertIsNotEnabled().assertIsOff()
        }
        compose.onNodeWithText("Название заказа").performTextReplacement("Заказ с отметками")
        compose.onNodeWithText("Курс, ₽/¥").performTextReplacement("10")
        compose.onNodeWithText("Новая позиция").performScrollTo().performClick()
        compose.onNodeWithText("Название товара").performTextReplacement("Товар с отметками")
        compose.onNodeWithText("Денису, ₽/шт").performScrollTo().performTextReplacement("100")
        compose.onNodeWithText("Вес единицы, кг").performScrollTo().performTextReplacement("0.2")
        compose.onNodeWithText("Тариф, ¥/кг").performTextReplacement("55")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Название товара").performScrollTo()
        captureSheet("position-editor.png")
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithText("Сохранить", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Заказ с отметками").fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { repository.observeOrders("", OrdersFilter.ALL, 50).first().single { it.order.counterparty == "Заказ с отметками" } }
        assertEquals(OrderFulfillment(), saved.order.fulfillment)
        assertEquals(GroupState.FORMING, saved.groupState)
    }

    @Test fun approvedFormulaAndMissingPurchaseAreConsistentInOrderAndGroup() {
        val command = SaveOrderCommand(commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"),
            counterparty = longTitle, receivedPayment = BigDecimal.ZERO, costsConfirmed = false,
            lines = listOf(OrderLineDraft(productName = "Контактор", quantity = BigDecimal.ONE,
                saleUnitPrice = BigDecimal("13000"), zeroSalePriceConfirmed = true)),
            costs = listOf(CostDraft(kind = CostKind.PURCHASE, amount = BigDecimal("8000"), date = LocalDate.parse("2026-11-11"))),
            manualDeliveryPrice = BigDecimal("200"))
        orderId = runBlocking { repository.saveOrder(command) }
        showOrder = true
        showScreen(1f)
        scrollToEditorField("Итого")
        compose.onNodeWithTag("order-total-1").assertTextEquals(Money(1280000).format())
        compose.onNodeWithTag("order-total-2").assertTextEquals(Money(800000).format())
        compose.onNodeWithTag("order-total-3").assertTextEquals(Money(480000).format())
        capture("formula-order.png")
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Группа №2 →").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Группа №2 →").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("group-total-Д").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("12 800 ₽", summaryText("Д"))
        assertEquals("8 000 ₽", summaryText("К"))
        assertEquals("4 800 ₽", summaryText("∆"))
        capture("formula-group.png")
        runBlocking {
            val line = repository.observeOrder(orderId).first()!!.lines.single()
            repository.saveOrder(command.copy(commandId = UUID.randomUUID(), orderId = orderId, costs = emptyList(),
                lines = listOf(command.lines.single().copy(id = line.id, productId = line.productId))))
        }
        compose.waitUntil(10_000) { summaryText("К") == "—" && summaryText("∆") == "—" }
        assertEquals("12 800 ₽", summaryText("Д"))
    }

    private fun scrollToEditorField(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(if (text == "order-actions") hasTestTag(text) else hasText(text))
    }

    private fun actionNode(action: OrderAction) = compose.onNodeWithTag("order-1-$action", useUnmergedTree = true)

    private fun waitAction(action: OrderAction, checked: Boolean) {
        compose.waitUntil(10_000) {
            runBlocking {
                val progress = repository.observeOrder(orderId).first()!!.order.fulfillment
                when (action) {
                    OrderAction.WITHDRAWN -> progress.withdrawn
                    OrderAction.TRANSFERRED -> progress.transferred
                    OrderAction.RECEIVED -> progress.received
                } == checked
            } && actionNode(action).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
                androidx.compose.ui.state.ToggleableState(checked)
        }
    }

    @Test fun largerFontKeepsTableAndActionsWithinScreen() {
        showScreen(1.3f, 320f)
        capture("group-large-font.png")
        assertFiveColumnActions()
        assertLongTitleAndValuesFit()
    }

    @Test fun phoneWidthKeepsFiveColumnsAndFullLongTitle() {
        showScreen(1.3f, 393f)
        compose.onNodeWithText("Действия").assertIsDisplayed()
        capture("group-inline-large-font.png")
        assertFiveColumnActions()
        assertLongTitleAndValuesFit()
    }

    private fun assertFiveColumnActions() {
        compose.onNodeWithText("Действия").assertIsDisplayed()
        val root = compose.onNodeWithTag("group-screen").fetchSemanticsNode().boundsInRoot
        val profit = compose.onNodeWithContentDescription("Прибыль: 120\u00a0000").fetchSemanticsNode().boundsInRoot
        val squares = OrderAction.entries.map { action ->
            val tag = "order-1-$action"
            val square = compose.onNodeWithTag("$tag-square", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val caption = compose.onNodeWithTag("$tag-caption", useUnmergedTree = true).assertIsDisplayed()
            val bounds = caption.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(bounds.top >= square.bottom)
            org.junit.Assert.assertTrue(bounds.right <= root.right && bounds.left >= profit.right)
            val layout = mutableListOf<TextLayoutResult>()
            caption.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertEquals(1, layout.single().lineCount)
            val result = layout.single()
            org.junit.Assert.assertFalse("$tag: bounds=$bounds size=${result.size}, widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, intrinsic=${result.multiParagraph.maxIntrinsicWidth}, style=${result.layoutInput.style}", result.hasVisualOverflow)
            org.junit.Assert.assertTrue(square.left >= profit.right)
            square
        }
        squares.zipWithNext().forEach { (left, right) ->
            org.junit.Assert.assertTrue(left.right < right.left)
            org.junit.Assert.assertEquals(left.top, right.top, 1f)
        }
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
            org.junit.Assert.assertFalse(values.single().hasVisualOverflow)
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
                            if (openedProduct != null) ProductDetailScreen(repository, openedProduct!!,
                                onBack = { openedProduct = null })
                            else if (showPrices) PricesScreen(repository, onOpenProduct = { openedProduct = it })
                            else if (showOrder) OrderEditorScreen(repository, if (newOrderPreview) null else orderId, onBack = { showOrder = false; showOrders = true })
                            else if (showOrders) MainScreenScaffold(previewTab, onSelectTab = { previewTab = it }, onSettings = {}) {
                                OrdersScreen(repository,
                                    onOpenOrder = { openedOrder = it; showOrders = false; showOrder = true }, onNewOrder = {},
                                    onOpenGroup = { openedGroup = it; groupId = it; showOrders = false })
                            }
                            else if (showGroups) GroupsScreen(repository, onOpenGroup = {})
                            else GroupDetailScreen(repository, groupId, onBack = { showOrders = true }, onOpenOrder = { openedOrder = it })
                        }
                    }
                }
            }
        }
        try {
            compose.waitUntil(10_000) {
                if (newOrderPreview && showOrder) compose.onAllNodesWithTag("order-new-WITHDRAWN", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                else if (showPrices) compose.onAllNodesWithText("Тестовая позиция").fetchSemanticsNodes().isNotEmpty()
                else if (showOrders) compose.onAllNodesWithTag("order-card-3").fetchSemanticsNodes().isNotEmpty()
                else compose.onAllNodesWithText(longTitle).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: ComposeTimeoutException) {
            compose.onRoot().printToLog("GroupScreenTest")
            runCatching { capture("group-timeout.png") }
            throw error
        }
    }

    private fun captureSheet(name: String) = saveImage(name, compose.onNodeWithTag("position-editor").captureToImage().asAndroidBitmap())

    private fun capture(name: String) = saveImage(name, compose.onNodeWithTag("group-screen").captureToImage().asAndroidBitmap())

    private fun saveImage(name: String, image: android.graphics.Bitmap) {
        val context: Context = ApplicationProvider.getApplicationContext()
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
        node.performScrollTo()
        val action = when (description) {
            "Получены деньги от Дениса" -> OrderAction.WITHDRAWN
            "Деньги за товар переданы посреднику" -> OrderAction.TRANSFERRED
            else -> OrderAction.RECEIVED
        }
        compose.onNodeWithTag("order-$orderNumber-$action-square", useUnmergedTree = true).performTouchInput { click(center) }
    }
}
