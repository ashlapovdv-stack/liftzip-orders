package ru.liftzip.orders.app.feature.settings

import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.liftzip.orders.app.feature.diagnostics.InvoiceDiagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(backup: BackupManager, settings: SettingsStore, onBack: () -> Unit) {
    val model = viewModel<SettingsViewModel>(factory = viewModelFactory { initializer { SettingsViewModel(backup, settings) } })
    val lastBackup by model.lastBackup.collectAsState()
    val autoBackup by settings.localBackup.collectAsState(initial = null)
    val galleryButtonFixed by settings.galleryButtonFixed.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    var diagnosticsEnabled by remember { mutableStateOf(InvoiceDiagnostics.enabled(context)) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }
    val backupFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folderUri = result.data?.data
        if (folderUri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(folderUri.authority == "com.android.externalstorage.documents") {
                        "Выберите папку во внутренней памяти телефона"
                    }
                    val permission = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    val folder = DocumentFile.fromTreeUri(context, folderUri)
                    require(folder != null && folder.isDirectory && folder.canWrite()) {
                        "Папка недоступна для записи"
                    }
                    context.contentResolver.takePersistableUriPermission(folderUri, permission)
                    settings.selectBackupFolder(folderUri, folder.name ?: "Выбранная папка")
                    DailyBackupSchedule.schedule(context)
                    DailyBackupSchedule.runNow(context, replace = true)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnosticMessage = error.message ?: "Не удалось подключить папку резервных копий"
            }
        }
    }
    val diagnosticsExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(InvoiceDiagnostics.read(context).toByteArray(Charsets.UTF_8))
                } ?: error("Не удалось открыть файл для записи")
            }.onSuccess { diagnosticMessage = "Диагностика экспортирована" }
                .onFailure { diagnosticMessage = "Ошибка экспорта: ${it.message}" }
        }
    }
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
            Text(
                "Версия ${packageInfo.versionName} (сборка ${PackageInfoCompat.getLongVersionCode(packageInfo)})",
                style = MaterialTheme.typography.bodySmall,
            )
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Закрепить кнопку добавления фото",
                            style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (galleryButtonFixed) "Кнопка остаётся слева при прокрутке фотографий"
                            else "Кнопка прокручивается вместе с фотографиями",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = galleryButtonFixed,
                        onCheckedChange = { fixed ->
                            scope.launch { settings.setGalleryButtonFixed(fixed) }
                        },
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Диагностика импорта PDF", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Записывать диагностику", modifier = Modifier.weight(1f))
                        Switch(checked = diagnosticsEnabled, onCheckedChange = {
                            diagnosticsEnabled = it
                            InvoiceDiagnostics.setEnabled(context, it)
                        })
                    }
                    Text("По умолчанию выключена. При включении сохраняются этапы распознавания и количество найденных позиций. Файлы остаются на устройстве, пока вы не экспортируете их вручную.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { diagnosticsExport.launch("Заказы-диагностика-PDF.txt") },
                        modifier = Modifier.fillMaxWidth()) { Text("Выгрузить диагностику") }
                    OutlinedButton(onClick = {
                        InvoiceDiagnostics.clear(context)
                        diagnosticMessage = "Диагностические записи удалены"
                    }, modifier = Modifier.fillMaxWidth()) { Text("Очистить диагностику") }
                }
            }
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
            autoBackup?.let { backupSettings ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ежедневная копия на телефоне", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Приложение хранит до 7 последних автоматических копий в выбранной папке.")
                        if (backupSettings.folder != null) {
                            Text("Папка: ${backupSettings.folderName}")
                            val latest = backupSettings.lastSuccess?.let { Instant.ofEpochMilli(it).formatDateTime() }
                            Text(latest?.let { "Последняя автокопия: $it" } ?: "Первая автокопия ещё не создана")
                            backupSettings.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            OutlinedButton(onClick = { DailyBackupSchedule.runNow(context) }) { Text("Создать копию сейчас") }
                        }
                        Button(onClick = {
                            backupFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                .putExtra(Intent.EXTRA_LOCAL_ONLY, true))
                        }) { Text(if (backupSettings.folder == null) "Выбрать папку и включить" else "Сменить папку") }
                        if (backupSettings.folder != null) {
                            TextButton(onClick = {
                                scope.launch {
                                    settings.selectBackupFolder(null)
                                    DailyBackupSchedule.cancel(context)
                                }
                            }) { Text("Отключить автокопии") }
                        }
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
                    if (!import.inspection.includesAttachments) Text("В этой старой копии нет вложений и черновиков. При восстановлении сохраняются доступные на устройстве файлы.")
                    Text("Текущая база будет полностью заменена. Перед этим будет создана локальная копия.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = model::confirmImport) { Text("Заменить данные") } },
            dismissButton = { TextButton(onClick = model::cancelImport) { Text("Отмена") } },
        )
    }
    diagnosticMessage?.let { text ->
        AlertDialog(onDismissRequest = { diagnosticMessage = null },
            text = { Text(text) }, confirmButton = {
                TextButton(onClick = { diagnosticMessage = null }) { Text("Понятно") }
            })
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = model::clearMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = model::clearMessage) { Text("Понятно") } },
        )
    }
}
