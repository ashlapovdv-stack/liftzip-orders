package ru.liftzip.orders.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/** Uses only platform APIs so it can exercise the installed, minified APK unchanged. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun releaseImportsBothInvoiceFormatsAndRestoresBackup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ordersReleaseSmoke") == "true")
        val context = instrumentation.targetContext
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val folder = File(context.cacheDir, "shared_letters").apply { mkdirs() }
        val pdf = File.createTempFile("release-invoice-", ".pdf", folder)
        val image = File.createTempFile("release-invoice-", ".png", folder)
        val backup = File.createTempFile("release-backup-", ".zip", folder)

        fun fileUri(file: File) = Uri.parse("content://${context.packageName}.files/draft_eml/${file.name}")

        try {
            writeTestInvoicePdf(pdf)
            writeTestInvoiceImage(image)
            instrumentation.startActivitySync(checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("Новый заказ")
            click("Импорт счёта")
            withPickerResult(Intent.ACTION_OPEN_DOCUMENT, fileUri(pdf)) {
                click("Файл PDF")
                node("Импорт товаров из счёта", timeout = 60_000)
            }
            node("5")
            node("8")
            click("Отмена")

            click("Импорт счёта")
            withPickerResult(Intent.ACTION_GET_CONTENT, fileUri(image)) {
                click("Галерея")
                node("Импорт товаров из счёта", timeout = 60_000)
            }
            node("5")
            node("8")
            click("Добавить 2")

            val title = "R8 тестовый заказ"
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
            }
            val field = awaitNode("поле названия заказа") { it.isEditable && it.isEnabled }
            assertTrue("Order title can be entered", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            click("Сохранить заказ")
            click("Настройки и резервные копии")
            click("Экспорт и восстановление")
            withPickerResult(Intent.ACTION_CREATE_DOCUMENT, fileUri(backup)) {
                click("Экспортировать копию", scroll = true)
                node("Резервная копия сохранена. Архив не зашифрован")
            }
            click("Понятно")
            ZipFile(backup).use { zip ->
                val payload = zip.getInputStream(zip.getEntry("data.json")).bufferedReader().use { JSONObject(it.readText()) }
                val orders = payload.getJSONArray("orders")
                assertTrue((0 until orders.length()).any { orders.getJSONObject(it).getString("counterparty") == title })
            }

            withPickerResult(Intent.ACTION_OPEN_DOCUMENT, fileUri(backup)) {
                click("Проверить и восстановить копию", scroll = true)
                click("Заменить данные")
                node("Данные восстановлены. Перед заменой создана локальная копия")
            }
            click("Понятно")
            click("Назад")
            node(title)
        } finally {
            pdf.delete()
            image.delete()
            backup.delete()
        }
    }

    private fun withPickerResult(action: String, uri: Uri, block: () -> Unit) {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? = if (intent.action == action) {
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            } else null
        }
        instrumentation.addMonitor(monitor)
        try {
            block()
            assertEquals("Picker action $action", 1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun click(label: String, scroll: Boolean = false) {
        val target = generateSequence(node(label, scroll = scroll)) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        assertTrue("Clicked $label", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun node(
        label: String,
        timeout: Long = 15_000,
        scroll: Boolean = false,
    ): AccessibilityNodeInfo = awaitNode(label, timeout, scroll) {
        it.text?.toString() == label || it.contentDescription?.toString() == label || it.hintText?.toString() == label
    }

    private fun awaitNode(
        label: String,
        timeout: Long = 15_000,
        scroll: Boolean = false,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        val automation = instrumentation.uiAutomation
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            root?.descendants()?.firstOrNull {
                it.isVisibleToUser && match(it)
            }?.let { return it }
            if (scroll && root != null) {
                root.descendants().firstOrNull { it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(200)
        }
        val visible = automation.rootInActiveWindow?.descendants()
            ?.mapNotNull { it.text ?: it.contentDescription }?.take(25)?.joinToString()
        error("No visible node '$label'. Visible text: $visible")
    }

    private fun AccessibilityNodeInfo.descendants(): Sequence<AccessibilityNodeInfo> =
        sequenceOf(this) + (0 until childCount).asSequence().mapNotNull { getChild(it) }.flatMap { it.descendants() }
}
