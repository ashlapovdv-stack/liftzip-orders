package ru.liftzip.orders.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.liftzip.orders.data.backup.BackupManager
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository

class OrdersApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch { container.repository.initialize() }
    }
}

class AppContainer(context: Context) {
    val database = OrdersDatabase.create(context)
    val repository = RoomOrdersRepository(database)
    val backup = BackupManager(context.applicationContext, database)
    val settings = SettingsStore(context.applicationContext)
}

private val Context.settingsDataStore by preferencesDataStore(name = "orders_settings")

class SettingsStore(context: Context) {
    private val dataStore = context.settingsDataStore
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
