package ru.liftzip.orders.app.feature.mail

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
fun GroupDraftScreen(repository: OrdersRepository, groupId: UUID, orderId: UUID, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(groupId) { GroupDraftStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<List<GroupDraftStore.Draft>>(emptyList()) }
    var selected by remember { mutableStateOf<UUID?>(orderId) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRefresh by remember { mutableStateOf(false) }

    suspend fun load() {
        busy = true
        try {
            drafts = withContext(Dispatchers.IO) {
                val details = repository.observeOrder(orderId).first()
                    ?: kotlin.error("Заказ не найден")
                require(details.order.groupId == groupId) { "Заказ не принадлежит группе" }
                if (store.get(groupId, orderId) == null) {
                    require(details.order.counterparty.isNotBlank() &&
                        details.lines.isNotEmpty() &&
                        details.lines.all { it.productNameSnapshot.isNotBlank() && it.quantity.signum() > 0 }) {
                        "Для письма нужны название заказа, товары и количество"
                    }
                    store.createIfAbsent(details)
                }
                store.list(groupId).filter { it.orderId == orderId }
            }
        } catch (e: Exception) { error = e.message }
        busy = false
    }
    LaunchedEffect(groupId) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onBack() }) { Text("← Назад") }
        Text("Письмо заказа", style = MaterialTheme.typography.titleLarge)
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
                drafts = store.list(groupId).filter { it.orderId == orderId }
            }
            OutlinedTextField(value = recipient, onValueChange = { recipient = it },
                label = { Text("Получатель") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = subject, onValueChange = { subject = it },
                label = { Text("Тема") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = body, onValueChange = { body = it },
                label = { Text("Текст письма") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp))
            val photos = remember(groupId, current.orderId, current.count) {
                store.attachments(groupId, current.orderId)
            }
            Text("Фотографии с номерами: ${photos.size}")
            if (photos.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photos.forEachIndexed { index, photo ->
                        val preview = remember(photo.absolutePath, photo.lastModified()) {
                            runCatching {
                                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                                BitmapFactory.decodeFile(photo.absolutePath, options)?.asImageBitmap()
                            }.getOrNull()
                        }
                        Column {
                            if (preview != null) {
                                Image(bitmap = preview, contentDescription = "Фото ${index + 1}, номер позиции нанесён",
                                    modifier = Modifier.size(104.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit)
                            } else {
                                Text("Ошибка фото ${index + 1}", color = MaterialTheme.colorScheme.error)
                            }
                            Text("Фото ${index + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
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
            OutlinedButton(onClick = { confirmRefresh = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Обновить из заказа")
            }
            if (confirmRefresh) {
                AlertDialog(onDismissRequest = { confirmRefresh = false },
                    title = { Text("Обновить черновик?") },
                    text = { Text("Тема, текст и фотографии будут заменены данными заказа. Получатель сохранится.") },
                    confirmButton = { TextButton(onClick = {
                        confirmRefresh = false
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val details = repository.observeOrder(current.orderId).first()
                                        ?: kotlin.error("Заказ не найден")
                                    store.refresh(details)
                                }
                                drafts = store.list(groupId)
                                selected = orderId
                            } catch (e: Exception) { error = e.message }
                        }
                    }) { Text("Обновить") } },
                    dismissButton = { TextButton(onClick = { confirmRefresh = false }) { Text("Отмена") } })
            }
        }
    }
}
