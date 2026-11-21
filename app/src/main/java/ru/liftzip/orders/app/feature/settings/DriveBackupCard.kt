package ru.liftzip.orders.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liftzip.orders.app.ui.formatDateTime
import java.time.Instant
import java.util.concurrent.TimeUnit

@Composable
internal fun DriveBackupSection(localEnabled: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { DriveBackupStore(context) }
    val config by store.settings.collectAsState(initial = DriveBackupSettings())
    val scope = rememberCoroutineScope()
    val queue by remember(context) { DriveBackupSchedule.observe(context) }.collectAsState(initial = emptyList())
    val uploading = queue.any { it.state == WorkInfo.State.RUNNING }
    val queued = queue.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    var submitting by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var newConnection by remember { mutableStateOf<Pair<String, String>?>(null) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    val client = remember(context) { Identity.getAuthorizationClient(context) }

    suspend fun retryUpload() {
        submitting = true
        notice = null
        try {
            DriveBackupSchedule.retryNow(context)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            notice = "Не удалось запустить отправку копии."
        } finally {
            submitting = false
        }
    }

    suspend fun connected(result: AuthorizationResult) {
        val token = result.accessToken ?: error("Google не предоставил доступ к Диску")
        val email = withContext(Dispatchers.IO) { DriveBackupApi(token).accountEmail() }
        if (config.email == email && config.sealedCode != null) {
            retryUpload()
        } else {
            newConnection = email to DriveArchive.newCode()
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        working = false
        if (result.resultCode == android.app.Activity.RESULT_OK) scope.launch {
            try {
                connected(client.getAuthorizationResultFromIntent(result.data))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notice = "Не удалось подключить Google Диск. Проверьте разрешение и настройку приложения в Google."
            }
        }
    }

    val lastUpload = config.lastUpload?.let { "Последняя загрузка: ${Instant.ofEpochMilli(it).formatDateTime()}" }
    val uploadStatus = when {
        !localEnabled || config.email == null -> null
        uploading -> "Отправляем копию в Google Диск…"
        submitting -> "Запускаем отправку…"
        queued -> if (config.error != null) "Ожидается повторная отправка." else "Копия в очереди на отправку."
        else -> null
    }
    SettingsSection(
        title = "Копия в Google Диске",
        summary = uploadStatus ?: when {
            config.email == null -> "Диск не подключён"
            !localEnabled -> "Автокопии на телефоне выключены"
            config.error != null -> "Проверьте состояние отправки"
            else -> lastUpload ?: "Подключено · загрузок пока нет"
        },
    ) {
        if (!localEnabled) Text("Сначала включите ежедневную копию на телефоне.")
        Text("В Google Диск отправляется зашифрованная копия. Для восстановления нужен код.")
        config.email?.let { Text(it) }
        lastUpload?.let { Text(it) }
        uploadStatus?.let { Text(it) }
        if (!uploading && !submitting) config.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (config.email != null) {
            Button(enabled = localEnabled && !submitting && !uploading && !working, onClick = {
                scope.launch { retryUpload() }
            }) { Text(if (uploading) "Копия отправляется…" else "Отправить последнюю копию") }
        }
        val authorize: () -> Unit = {
            working = true
            notice = null
            scope.launch {
                var pending = false
                try {
                    val result = withContext(Dispatchers.IO) {
                        Tasks.await(client.authorize(driveAuthorization(config.email)), 30, TimeUnit.SECONDS)
                    }
                    val resolution = result.pendingIntent
                    if (resolution == null) connected(result)
                    else {
                        consent.launch(IntentSenderRequest.Builder(resolution.intentSender).build())
                        pending = true
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    notice = "Не удалось подключить Google Диск. Проверьте разрешение и настройку приложения в Google."
                } finally {
                    if (!pending) working = false
                }
            }
        }
        if (config.email == null) {
            Button(enabled = localEnabled && !working, onClick = authorize) { Text("Подключить Google Диск") }
        } else {
            OutlinedButton(enabled = localEnabled && !working, onClick = authorize) { Text("Обновить доступ") }
        }

        if (config.email != null) {
            TextButton(onClick = {
                val sealedCode = config.sealedCode
                if (sealedCode != null) scope.launch {
                    try {
                        recoveryCode = withContext(Dispatchers.IO) { DriveRecoveryCode.unseal(sealedCode) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        notice = "Не удалось прочитать сохранённый код."
                    }
                }
            }) { Text("Показать код восстановления") }
            TextButton(onClick = { scope.launch {
                store.disconnect()
                DriveBackupSchedule.cancel(context)
            } }) { Text("Отключить Google Диск") }
        }
    }

    newConnection?.let { (email, code) ->
        var saved by remember(code) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { newConnection = null },
            title = { Text("Сохраните код восстановления") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Код понадобится после переустановки приложения или на новом телефоне.")
                    RecoveryCodeValue(code)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = saved, onCheckedChange = { saved = it })
                        Text("Я сохранил код")
                    }
                }
            },
            confirmButton = { TextButton(enabled = saved && !working, onClick = {
                working = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.connect(email, code) }
                        DriveBackupSchedule.enqueue(context)
                        newConnection = null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        notice = "Не удалось сохранить подключение."
                        newConnection = null
                    } finally { working = false }
                }
            }) { Text("Включить загрузку") } },
            dismissButton = { TextButton(onClick = { newConnection = null }) { Text("Отмена") } },
        )
    }
    recoveryCode?.let { code ->
        AlertDialog(
            onDismissRequest = { recoveryCode = null },
            title = { Text("Код восстановления") },
            text = { RecoveryCodeValue(code) },
            confirmButton = { TextButton(onClick = { recoveryCode = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun RecoveryCodeValue(code: String) {
    val clipboard = LocalClipboardManager.current
    Column {
        SelectionContainer { Text(code) }
        TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Скопировать код") }
    }
}

@Composable
internal fun RecoveryCodeDialog(onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Код восстановления копии") },
        text = { OutlinedTextField(
            value = code, onValueChange = { code = it.trim() },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            label = { Text("Код восстановления") },
        ) },
        confirmButton = { TextButton(enabled = code.length == 32, onClick = { onConfirm(code) }) { Text("Проверить копию") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
