package ru.liftzip.orders.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.liftzip.orders.app.SettingsStore
import ru.liftzip.orders.data.backup.BackupInspection
import ru.liftzip.orders.data.backup.BackupManager
import ru.liftzip.orders.domain.model.GroupDetails
import ru.liftzip.orders.domain.model.GroupSummary
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.model.OrdersFilter
import ru.liftzip.orders.domain.model.Product
import ru.liftzip.orders.domain.model.ProductPriceHistory
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.time.Instant
import java.util.UUID

class MainViewModel(private val settings: SettingsStore) : ViewModel() {
    val selectedTab = settings.selectedTab.stateIn(viewModelScope, SharingStarted.Eagerly, 2)
    fun selectTab(index: Int) = viewModelScope.launch { settings.selectTab(index) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModel(private val repository: OrdersRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(OrdersFilter.ALL)
    private val limit = MutableStateFlow(50)

    val orders = combine(query, filter, limit) { q, f, l -> Triple(q, f, l) }
        .flatMapLatest { (q, f, l) -> repository.observeOrders(q, f, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val currentQuery = query.asStateFlow()
    val currentFilter = filter.asStateFlow()
    val currentLimit = limit.asStateFlow()

    fun search(value: String) { query.value = value; limit.value = 50 }
    fun filter(value: OrdersFilter) { filter.value = value; limit.value = 50 }
    fun showMore() { limit.value = (limit.value + 50).coerceAtMost(500) }
}

class GroupsViewModel(repository: OrdersRepository) : ViewModel() {
    val groups = repository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<GroupSummary>())
}

class PriceSearchViewModel(private val repository: OrdersRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    val currentQuery = query.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val products = query.flatMapLatest { repository.observeProducts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Product>())
    fun search(value: String) { query.value = value }
}

class GroupDetailViewModel(
    private val repository: OrdersRepository,
    groupId: UUID,
) : ViewModel() {
    val details = repository.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null as GroupDetails?)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var finishCommandId = UUID.randomUUID()

    fun finish(groupId: UUID, onFinished: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                repository.finishGroup(ru.liftzip.orders.domain.model.FinishGroupCommand(finishCommandId, groupId))
                finishCommandId = UUID.randomUUID()
                onFinished()
            } catch (error: Exception) {
                _message.value = error.message ?: "Не удалось завершить формирование"
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
}

class ProductDetailViewModel(repository: OrdersRepository, productId: UUID) : ViewModel() {
    val history = repository.observeProductHistory(productId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null as ProductPriceHistory?)
}

class SettingsViewModel(
    private val backup: BackupManager,
    private val settings: SettingsStore,
) : ViewModel() {
    val lastBackup = settings.lastSuccessfulBackup.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport = _pendingImport.asStateFlow()
    private val _recoveryAvailable = MutableStateFlow(backup.latestRecoveryCopy() != null)
    val recoveryAvailable = _recoveryAvailable.asStateFlow()

    fun export(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = backup.exportTo(uri)
                settings.recordSuccessfulBackup(result.createdAt)
                _message.value = "Резервная копия сохранена. Архив не зашифрован"
            } catch (error: Exception) {
                _message.value = error.message ?: "Экспорт не выполнен; дата копии не обновлена"
            } finally { _busy.value = false }
        }
    }

    fun inspect(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                _pendingImport.value = PendingImport(uri, backup.inspect(uri))
            } catch (error: Exception) {
                _message.value = error.message ?: "Резервная копия не прошла проверку"
            } finally { _busy.value = false }
        }
    }

    fun inspectLatestRecovery() {
        val file = backup.latestRecoveryCopy() ?: return
        inspect(Uri.fromFile(file))
    }

    fun confirmImport() {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        _busy.value = true
        viewModelScope.launch {
            try {
                backup.importFrom(pending.uri)
                _recoveryAvailable.value = true
                _message.value = "Данные восстановлены. Перед заменой создана локальная копия"
            } catch (error: Exception) {
                _message.value = error.message ?: "Восстановление не выполнено; текущие данные сохранены"
            } finally { _busy.value = false }
        }
    }

    fun cancelImport() { _pendingImport.value = null }
    fun clearMessage() { _message.value = null }
}

data class PendingImport(val uri: Uri, val inspection: BackupInspection)
