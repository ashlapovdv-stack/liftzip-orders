package ru.liftzip.orders.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsVersionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsShowInstalledVersionAndBuildNumber() {
        val activity = compose.activity
        val app = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val version = "Версия ${app.versionName} (сборка ${PackageInfoCompat.getLongVersionCode(app)})"

        openSettings()
        compose.onNodeWithText(version).performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsSectionsRevealTheirActionsAndCanBeCollapsed() {
        openSettings()

        val sections = listOf(
            "Ежедневная копия на телефоне" to "Выбрать папку и включить",
            "Копия в Google Диске" to "Подключить Google Диск",
            "Экспорт и восстановление" to "Восстановить зашифрованную копию",
            "Фотографии в заказах" to "Закрепить кнопку добавления фото",
            "Диагностика импорта PDF" to "Выгрузить диагностику",
        )
        for ((title, action) in sections) {
            val section = compose.onNodeWithText(title)
            val control = compose.onNodeWithText(action)
            control.assertDoesNotExist()
            section.performScrollTo().performClick()
            control.performScrollTo().assertIsDisplayed()
            section.performScrollTo().performClick()
            control.assertDoesNotExist()
        }
    }

    private fun openSettings() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Настройки и резервные копии").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Настройки и резервные копии").performClick()
    }
}
