package ru.liftzip.orders.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.function.Predicate;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Exercises the installed APK without linking to app or Kotlin runtime classes. */
@RunWith(AndroidJUnit4.class)
public class ReleaseSmokeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private static final long UI_TIMEOUT = 15_000;

    @Test public void releaseImportsBothInvoiceFormatsAndRestoresBackup() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("ordersReleaseSmoke")));
        Context context = instrumentation.getTargetContext();
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE);
        File folder = new File(context.getCacheDir(), "shared_letters");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        File pdf = File.createTempFile("release-invoice-", ".pdf", folder);
        File image = File.createTempFile("release-invoice-", ".png", folder);
        File backup = File.createTempFile("release-backup-", ".zip", folder);

        try {
            InvoiceTestFixture.writePdf(pdf);
            InvoiceTestFixture.writeImage(image);
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            instrumentation.startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click("Новый заказ");
            checkInvoice(Intent.ACTION_OPEN_DOCUMENT, "Файл PDF", pdf, "Отмена");
            checkInvoice(Intent.ACTION_GET_CONTENT, "Галерея", image, "Добавить 2");

            String title = "R8 тестовый заказ";
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title);
            AccessibilityNodeInfo field = await("поле названия заказа", UI_TIMEOUT, false,
                    node -> node.isEditable() && node.isEnabled());
            assertTrue("Order title can be entered", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
            click("Сохранить заказ");
            click("Настройки и резервные копии");
            click("Экспорт и восстановление");
            withPickerResult(Intent.ACTION_CREATE_DOCUMENT, backup, () -> {
                click("Экспортировать копию", true);
                node("Резервная копия сохранена. Архив не зашифрован", UI_TIMEOUT, false);
            });
            click("Понятно");
            try (ZipFile zip = new ZipFile(backup)) {
                String payload = new String(zip.getInputStream(zip.getEntry("data.json")).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                JSONArray orders = new JSONObject(payload).getJSONArray("orders");
                boolean saved = false;
                for (int i = 0; i < orders.length(); i++) {
                    saved |= title.equals(orders.getJSONObject(i).getString("counterparty"));
                }
                assertTrue("Archive contains the new order", saved);
            }

            withPickerResult(Intent.ACTION_OPEN_DOCUMENT, backup, () -> {
                click("Проверить и восстановить копию", true);
                click("Заменить данные");
                node("Данные восстановлены. Перед заменой создана локальная копия", UI_TIMEOUT, false);
            });
            click("Понятно");
            click("Назад");
            node(title, UI_TIMEOUT, false);
        } finally {
            pdf.delete();
            image.delete();
            backup.delete();
        }
    }

    private void checkInvoice(String action, String picker, File invoice, String done) {
        click("Импорт счёта");
        withPickerResult(action, invoice, () -> {
            click(picker);
            node("Импорт товаров из счёта", 60_000, false);
        });
        node("5", UI_TIMEOUT, false);
        node("8", UI_TIMEOUT, false);
        click(done);
    }

    private void withPickerResult(String action, File file, Runnable block) {
        Uri uri = Uri.parse("content://" + instrumentation.getTargetContext().getPackageName()
                + ".files/draft_eml/" + file.getName());
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                return action.equals(intent.getAction())
                        ? new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(uri))
                        : null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            block.run();
            assertEquals("Picker action " + action, 1, monitor.getHits());
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void click(String label) {
        click(label, false);
    }

    private void click(String label, boolean scroll) {
        AccessibilityNodeInfo node = node(label, UI_TIMEOUT, scroll);
        while (node != null && (!node.isClickable() || !node.isEnabled())) node = node.getParent();
        assertTrue("Clicked " + label, node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private AccessibilityNodeInfo node(String label, long timeout, boolean scroll) {
        return await(label, timeout, scroll, node -> label.contentEquals(text(node.getText()))
                || label.contentEquals(text(node.getContentDescription()))
                || label.contentEquals(text(node.getHintText())));
    }

    private AccessibilityNodeInfo await(String label, long timeout, boolean scroll,
                                        Predicate<AccessibilityNodeInfo> match) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo found = find(root, node -> node.isVisibleToUser() && match.test(node));
            if (found != null) return found;
            if (scroll) {
                AccessibilityNodeInfo list = find(root, AccessibilityNodeInfo::isScrollable);
                if (list != null) list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("No visible node: " + label);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> match) {
        if (node == null || match.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), match);
            if (found != null) return found;
        }
        return null;
    }

    private static CharSequence text(CharSequence value) {
        return value == null ? "" : value;
    }
}
