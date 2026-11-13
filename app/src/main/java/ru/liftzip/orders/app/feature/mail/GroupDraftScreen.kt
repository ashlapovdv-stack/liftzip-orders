package ru.liftzip.orders.app.feature.mail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.io.File
import java.util.UUID

@Composable
fun GroupDraftScreen(repository: OrdersRepository, groupId: UUID, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(groupId) { GroupDraftStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<List<GroupDraftStore.Draft>>(emptyList()) }
    var selected by remember { mutableStateOf<UUID?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        busy = true
        try {
            drafts = withContext(Dispatchers.IO) {
                val group = repository.observeGroup(groupId).first() ?: kotlin.error("Группа не найдена")
                check(group.summary.group.state != ru.liftzip.orders.domain.model.GroupState.FORMING) {
                    "Сначала завершите формирование группы"
                }
                group.orders.forEach { summary ->
                    val details = repository.observeOrder(summary.order.id).first()
                    if (details != null) store.createIfAbsent(details)
                }
                store.list(groupId)
            }
        } catch (e: Exception) { error = e.message }
        busy = false
    }
    LaunchedEffect(groupId) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = { if (selected == null) onBack() else selected = null }) { Text("← Назад") }
        Text("Черновики группы", style = MaterialTheme.typography.titleLarge)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        val current = selected?.let { id -> drafts.find { it.orderId == id } }
        if (current == null) {
            LazyColumn {
                items(drafts, key = { it.orderId }) { draft ->
                    ListItem(
                        headlineContent = { Text(draft.subject) },
                        supportingContent = { Text("Вложений: ${draft.count} · Черновик") },
                        modifier = Modifier.clickable { selected = draft.orderId },
                    )
                    HorizontalDivider()
                }
            }
        } else {
            var subject by remember(current.orderId) { mutableStateOf(current.subject) }
            var recipient by remember(current.orderId) { mutableStateOf(current.recipient) }
            var body by remember(current.orderId) { mutableStateOf(current.body) }
            fun save() {
                store.update(groupId, current.orderId, recipient, subject, body)
                drafts = store.list(groupId)
            }
            OutlinedTextField(value = recipient, onValueChange = { recipient = it },
                label = { Text("Получатель") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = subject, onValueChange = { subject = it },
                label = { Text("Тема") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = body, onValueChange = { body = it },
                label = { Text("Текст письма") }, modifier = Modifier.fillMaxWidth().weight(1f))
            Text("Фотографий: ${current.count}")
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить черновик") }
            Button(onClick = {
                save()
                val files = store.attachments(groupId, current.orderId)
                val uris = ArrayList<Uri>(files.map {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", it)
                })
                val intent = Intent(if (uris.isEmpty()) Intent.ACTION_SENDTO else Intent.ACTION_SEND_MULTIPLE).apply {
                    if (uris.isEmpty()) {
                        data = Uri.parse("mailto:")
                    } else {
                        type = "image/jpeg"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newUri(context.contentResolver, "Фотографии", uris.first()).also { clip ->
                            uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                        }
                    }
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient).filter { it.isNotBlank() }.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Поделиться письмом")) }
                    .onFailure { error = it.message }
            }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться") }
            OutlinedButton(onClick = {
                save()
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            EmlExporter.export(context, groupId, current.orderId, store, subject, recipient, body)
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "message/rfc822"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Экспорт .eml"))
                    } catch (e: Exception) { error = e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Экспорт .eml") }
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val details = repository.observeOrder(current.orderId).first() ?: kotlin.error("Заказ не найден")
                            store.refresh(details)
                        }
                        drafts = store.list(groupId)
                        selected = null
                    } catch (e: Exception) { error = e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Обновить из заказа") }
        }
    }
}
