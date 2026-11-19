package ru.liftzip.orders.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        compose.onNodeWithContentDescription("Настройки и резервные копии").performClick()
        compose.onNodeWithText(version).assertIsDisplayed()
    }
}
