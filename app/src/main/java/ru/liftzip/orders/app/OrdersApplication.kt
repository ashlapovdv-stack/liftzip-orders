package ru.liftzip.orders.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.liftzip.orders.data.backup.BackupManager
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository

class OrdersApplication : Application() {
    val settings by lazy { SettingsStore(this) }
    private val recoveryPreferences by lazy { getSharedPreferences("database_location", MODE_PRIVATE) }

    private val opening = Mutex()
    private var active: AppContainer? = null

    suspend fun openOrders(): AppContainer = withContext(Dispatchers.IO) {
        opening.withLock { active ?: openActiveDatabase().also { active = it } }
    }

    private suspend fun openActiveDatabase(): AppContainer {
        val name = recoveryPreferences.getString("active", OrdersDatabase.DATABASE_NAME)
            ?: OrdersDatabase.DATABASE_NAME
        val container = AppContainer(this@OrdersApplication, name)
        try {
            container.backup.recoverPendingRestore()
            container.repository.initialize()
            return container
        } catch (error: Exception) {
            container.database.close()
            throw error
        }
    }

    suspend fun restoreOrders(uri: Uri): AppContainer = withContext(Dispatchers.IO) {
        val name = "liftzip-recovered-${UUID.randomUUID()}.db"
        val container = AppContainer(this@OrdersApplication, name)
        try {
            container.backup.restoreIntoNewDatabase(uri) {
                check(recoveryPreferences.edit().putString("active", name).commit()) {
                    "Не удалось выбрать восстановленную базу"
                }
            }
            container.repository.initialize()
            opening.withLock { active = container }
            container
        } catch (error: Exception) {
            container.database.close()
            throw error
        }
    }
}

class AppContainer(context: Context, databaseName: String = OrdersDatabase.DATABASE_NAME) {
    val database = OrdersDatabase.create(context, databaseName)
    val repository = RoomOrdersRepository(database)
    val backup = BackupManager(context.applicationContext, database)
    val settings = SettingsStore(context.applicationContext)
}

private val Context.settingsDataStore by preferencesDataStore(name = "orders_settings")

class SettingsStore(context: Context) {
    private val dataStore = context.settingsDataStore
    private val galleryButtonFixedKey = booleanPreferencesKey("gallery_button_fixed")
    val galleryButtonFixed: Flow<Boolean> = dataStore.data.map { it[galleryButtonFixedKey] ?: true }
    suspend fun setGalleryButtonFixed(fixed: Boolean) { dataStore.edit { it[galleryButtonFixedKey] = fixed } }
    private val selectedTabKey = intPreferencesKey("selected_tab")
    private val lastBackupKey = longPreferencesKey("last_successful_backup")

    val selectedTab: Flow<Int> = dataStore.data.map { it[selectedTabKey] ?: 2 }
    val lastSuccessfulBackup: Flow<Long?> = dataStore.data.map { it[lastBackupKey] }

    suspend fun selectTab(index: Int) {
        dataStore.edit { it[selectedTabKey] = index }
    }

    suspend fun recordSuccessfulBackup(time: Long) {
        dataStore.edit { it[lastBackupKey] = time }
    }
}
