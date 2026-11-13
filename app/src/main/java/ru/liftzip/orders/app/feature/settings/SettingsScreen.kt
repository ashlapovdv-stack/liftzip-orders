package ru.liftzip.orders.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.SettingsStore
import ru.liftzip.orders.app.ui.SettingsViewModel
import ru.liftzip.orders.app.ui.formatDateTime
import ru.liftzip.orders.data.backup.BackupManager
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(backup: BackupManager, settings: SettingsStore, onBack: () -> Unit) {
    val model = viewModel<SettingsViewModel>(factory = viewModelFactory { initializer { SettingsViewModel(backup, settings) } })
    val lastBackup by model.lastBackup.collectAsState()
    val galleryButtonFixed by settings.galleryButtonFixed.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val pending by model.pendingImport.collectAsState()
    val recoveryAvailable by model.recoveryAvailable.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(model::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::inspect)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки и копии") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Локальные данные", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Без сервера, регистрации и облачной базы. Системные облачные копии отключены.")
                    Text("Резервные ZIP-архивы не зашифрованы и содержат введённые заказы. Храните их в защищённом месте.", color = MaterialTheme.colorScheme.error)
                    if (lastBackup == null) {
                        Text("Успешная экспортированная копия ещё не зарегистрирована", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Последняя успешная копия: ${Instant.ofEpochMilli(lastBackup!!).formatDateTime()}")
                    }
                }
            }
            Button(
                onClick = { exportLauncher.launch("Заказы-резервная-копия.zip") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Экспортировать копию") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Проверить и восстановить копию") }
            if (recoveryAvailable) {
                OutlinedButton(
                    onClick = model::inspectLatestRecovery, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Восстановить копию перед последним импортом") }
            }
            Text("Восстановление заменяет текущие данные, не объединяет их. Перед заменой приложение создаёт локальную копию. Повреждённый или неподдерживаемый архив не изменяет базу.")
            if (busy) Text("Выполняется проверка или запись…")
        }
    }

    pending?.let { import ->
        AlertDialog(
            onDismissRequest = model::cancelImport,
            title = { Text("Заменить текущие данные?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Копия создана: ${Instant.ofEpochMilli(import.inspection.createdAt).formatDateTime()}")
                    Text("Всего записей: ${import.inspection.recordCount}")
                    Text("Заказов: ${import.inspection.counts["orders"] ?: 0}; групп: ${import.inspection.counts["groups"] ?: 0}; товаров: ${import.inspection.counts["products"] ?: 0}")
                    Text("Текущая база будет полностью заменена. Перед этим будет создана локальная копия.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = model::confirmImport) { Text("Заменить данные") } },
            dismissButton = { TextButton(onClick = model::cancelImport) { Text("Отмена") } },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = model::clearMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = model::clearMessage) { Text("Понятно") } },
        )
    }
}
