package ru.liftzip.orders.app

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
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
    private lateinit var orderId: UUID
    private lateinit var groupId: UUID
    private var openedOrder: UUID? = null

    @Before fun setUp(): Unit = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrdersDatabase::class.java).build()
        repository = RoomOrdersRepository(database)
        repository.initialize()
        orderId = repository.saveOrder(SaveOrderCommand(
            commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = "Заказ для Дениса",
            receivedPayment = BigDecimal.ZERO, costsConfirmed = false,
            lines = listOf(OrderLineDraft(productName = "Контактор", quantity = BigDecimal.ONE,
                saleUnitPrice = BigDecimal("240000"), zeroSalePriceConfirmed = true)),
            costs = listOf(CostDraft(kind = CostKind.PURCHASE, amount = BigDecimal("120000"),
                date = LocalDate.parse("2026-11-11"))), manualDeliveryPrice = BigDecimal.ZERO,
        ))
        groupId = repository.observeOrder(orderId).first()!!.group.id
        repository.finishGroup(FinishGroupCommand(UUID.randomUUID(), groupId))
    }

    @After fun tearDown() { database.close() }

    @Test fun compactTableShowsAllAmountsAndActionsCompleteAndReopenGroup() {
        showScreen(1f)
        compose.onNodeWithText("Полученная оплата: 0 ₽").assertDoesNotExist()
        compose.onNodeWithText("Статус «На расчёте» не означает утверждение расчёта или подтверждение выкупа").assertDoesNotExist()
        for (title in listOf("Д", "К", "∆", "Снял", "Передал", "Получено")) compose.onNodeWithText(title).assertIsDisplayed()
        capture("group-table.png")
        for ((action, description) in listOf(
            OrderAction.WITHDRAWN to "Получены деньги от Дениса",
            OrderAction.TRANSFERRED to "Деньги за товар переданы посреднику",
            OrderAction.RECEIVED to "Товар получен",
        )) {
            clickAction(description)
            compose.waitUntil(10_000) {
                runBlocking {
                    val p = repository.observeOrder(orderId).first()!!.order.fulfillment
                    when (action) {
                        OrderAction.WITHDRAWN -> p.withdrawn
                        OrderAction.TRANSFERRED -> p.transferred
                        OrderAction.RECEIVED -> p.received
                    }
                }
            }
            compose.waitForIdle()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Выполнена").fetchSemanticsNodes().isNotEmpty() }
        capture("group-completed.png")
        clickAction("Товар получен")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("На расчёте").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Заказ №1").performClick()
        assertEquals(orderId, openedOrder)
    }

    @Test fun largerFontKeepsTableAndActionsWithinScreen() {
        showScreen(1.3f)
        val rootWidth = compose.onNodeWithTag("group-screen").fetchSemanticsNode().boundsInRoot.width
        for (label in listOf("Д", "К", "∆", "Снял", "Передал", "Получено")) {
            val node = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
            org.junit.Assert.assertTrue(node.boundsInRoot.right <= rootWidth + 1)
            org.junit.Assert.assertTrue(node.boundsInRoot.left >= 0)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Получено", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        capture("group-large-font.png")
    }

    private fun showScreen(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OrdersTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(320.dp).testTag("group-screen")) {
                            GroupDetailScreen(repository, groupId, onBack = {}, onOpenOrder = { openedOrder = it })
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Заказ №1").fetchSemanticsNodes().isNotEmpty() }
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

    private fun clickAction(description: String) {
        val node = compose.onNodeWithContentDescription("Заказ №1: $description")
        compose.waitUntil(10_000) { !node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        node.performClick()
    }
}
