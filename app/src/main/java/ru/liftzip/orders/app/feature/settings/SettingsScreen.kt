package ru.liftzip.orders.app.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liftzip.orders.app.SettingsStore
import ru.liftzip.orders.app.feature.diagnostics.InvoiceDiagnostics
import ru.liftzip.orders.app.ui.SettingsViewModel
import ru.liftzip.orders.app.ui.formatDateTime
import ru.liftzip.orders.data.backup.BackupManager

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
    var encryptedBackup by rememberSaveable { mutableStateOf<Uri?>(null) }
    val encryptedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        encryptedBackup = uri
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::inspect)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsGroupTitle("Резервные копии")
            autoBackup?.let { backupSettings ->
                val latest = backupSettings.lastSuccess?.let { Instant.ofEpochMilli(it).formatDateTime() }
                SettingsSection(
                    title = "Ежедневная копия на телефоне",
                    summary = when {
                        backupSettings.folder == null -> "Автокопии выключены"
                        backupSettings.error != null -> "Проверьте состояние копии"
                        latest != null -> "Последняя автокопия: $latest"
                        else -> "Первая автокопия ещё не создана"
                    },
                ) {
                    Text("В выбранной папке хранятся до 7 последних автокопий.", style = MaterialTheme.typography.bodyMedium)
                    if (backupSettings.folder != null) {
                        Text("Папка: ${backupSettings.folderName}")
                        Text(latest?.let { "Последняя автокопия: $it" } ?: "Первая автокопия ещё не создана")
                        backupSettings.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { DailyBackupSchedule.runNow(context) }) { Text("Создать копию сейчас") }
                    }
                    val chooseFolder = {
                        backupFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true))
                    }
                    if (backupSettings.folder == null) {
                        Button(onClick = chooseFolder) { Text("Выбрать папку и включить") }
                    } else {
                        OutlinedButton(onClick = chooseFolder) { Text("Сменить папку") }
                        TextButton(onClick = {
                            scope.launch {
                                settings.selectBackupFolder(null)
                                DailyBackupSchedule.cancel(context)
                            }
                        }) { Text("Отключить автокопии") }
                    }
                }
            }
            DriveBackupSection(localEnabled = autoBackup?.folder != null)
            SettingsSection("Экспорт и восстановление", if (busy) "Выполняется проверка или запись…" else "Ручная работа с ZIP-архивами") {
                val exportedAt = lastBackup?.let { Instant.ofEpochMilli(it).formatDateTime() }
                Text(exportedAt?.let { "Последний экспорт: $it" } ?: "Копии ещё не экспортировались")
                Text(
                    "Локальные ZIP-архивы содержат заказы без шифрования. Храните их в защищённом месте.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { exportLauncher.launch("Заказы-резервная-копия.zip") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text("Экспортировать копию") }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "При восстановлении данные из архива заменят текущие. Перед заменой приложение создаст локальную копию.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text("Проверить и восстановить копию") }
                OutlinedButton(
                    onClick = { encryptedPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text("Восстановить зашифрованную копию") }
                if (recoveryAvailable) {
                    TextButton(
                        onClick = model::inspectLatestRecovery, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Восстановить копию перед последним импортом") }
                }
                if (busy) Text("Выполняется проверка или запись…")
            }
            SettingsGroupTitle("Приложение")
            SettingsSection(
                title = "Фотографии в заказах",
                summary = if (galleryButtonFixed) "Кнопка добавления закреплена" else "Кнопка добавления прокручивается",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Закрепить кнопку добавления фото",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = galleryButtonFixed,
                        onCheckedChange = { fixed -> scope.launch { settings.setGalleryButtonFixed(fixed) } },
                    )
                }
            }
            SettingsSection("Диагностика импорта PDF", if (diagnosticsEnabled) "Запись включена" else "Запись выключена") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Записывать диагностику", modifier = Modifier.weight(1f))
                    Switch(checked = diagnosticsEnabled, onCheckedChange = {
                        diagnosticsEnabled = it
                        InvoiceDiagnostics.setEnabled(context, it)
                    })
                }
                Text(
                    "Записи об этапах распознавания и количестве найденных позиций хранятся на телефоне до ручного экспорта.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { diagnosticsExport.launch("Заказы-диагностика-PDF.txt") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выгрузить диагностику") }
                TextButton(onClick = {
                    InvoiceDiagnostics.clear(context)
                    diagnosticMessage = "Диагностические записи удалены"
                }) { Text("Очистить диагностику") }
            }
            Text(
                "Версия ${packageInfo.versionName} (сборка ${PackageInfoCompat.getLongVersionCode(packageInfo)})",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    encryptedBackup?.let { uri ->
        RecoveryCodeDialog(
            onCancel = { encryptedBackup = null },
            onConfirm = { code ->
                encryptedBackup = null
                model.inspectEncrypted(context.applicationContext, uri, code)
            },
        )
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


@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun SettingsSection(
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Раздел открыт" else "Раздел свёрнут" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (!expanded) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}
