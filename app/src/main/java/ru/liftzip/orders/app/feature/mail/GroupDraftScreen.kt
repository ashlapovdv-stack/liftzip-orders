package ru.liftzip.orders.app.feature.mail

import android.content.Intent
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Description
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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
    var enlargedPhoto by remember { mutableStateOf<File?>(null) }
    var draftVersion by remember { mutableIntStateOf(0) }

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

    val darkGreen = Color(0xFF195B47)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().background(Color.White).verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", modifier = Modifier.size(30.dp))
            }
            Text("Письмо заказа", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        val current = selected?.let { id -> drafts.find { it.orderId == id } }
        if (current == null) {
            Column { 
                drafts.forEach { draft ->
                    ListItem(
                        headlineContent = { Text(draft.subject) },
                        supportingContent = { Text("Вложений: ${draft.count} · Черновик") },
                        modifier = Modifier.clickable { selected = draft.orderId },
                    )
                    HorizontalDivider()
                }
            }
        } else {
            var subject by remember(current.orderId, draftVersion) { mutableStateOf(current.subject) }
            var recipient by remember(current.orderId, draftVersion) { mutableStateOf(current.recipient) }
            var body by remember(current.orderId, draftVersion) { mutableStateOf(current.body) }
            fun save() {
                store.update(groupId, current.orderId, recipient, subject, body)
                drafts = store.list(groupId).filter { it.orderId == orderId }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text("Получатель", color = Color(0xFF777777), fontSize = 13.sp)
                BasicTextField(value = recipient, onValueChange = { recipient = it },
                    singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF202020)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(top = 8.dp))
                HorizontalDivider(color = Color(0xFFB8B8B8))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text("Тема", color = Color(0xFF777777), fontSize = 13.sp)
                BasicTextField(value = subject, onValueChange = { subject = it },
                    singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF202020)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(top = 8.dp))
                HorizontalDivider(color = Color(0xFFB8B8B8))
            }
            Text("Текст письма", color = darkGreen, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            BasicTextField(value = body, onValueChange = { body = it },
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = Color(0xFF202020)),
                modifier = Modifier.fillMaxWidth().height(245.dp)
                    .border(1.dp, Color(0xFFCECECE), RoundedCornerShape(10.dp))
                    .padding(12.dp))
            val photos = remember(groupId, current.orderId, current.count) {
                store.attachments(groupId, current.orderId)
            }
            val documents = remember(groupId, current.orderId, draftVersion) { store.documents(groupId, current.orderId) }
            if (photos.isNotEmpty() || documents.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    photos.forEachIndexed { index, photo ->
                        val preview = remember(photo.absolutePath, photo.lastModified()) {
                            runCatching {
                                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                                BitmapFactory.decodeFile(photo.absolutePath, options)?.asImageBitmap()
                            }.getOrNull()
                        }
                        Box {
                            if (preview != null) {
                                Image(bitmap = preview, contentDescription = "Фото ${index + 1}, номер позиции нанесён",
                                    modifier = Modifier.size(104.dp).clip(RoundedCornerShape(10.dp))
                                        .clickable { enlargedPhoto = photo },
                                    contentScale = ContentScale.Fit)
                            } else {
                                Text("Ошибка фото ${index + 1}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (documents.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    documents.forEach { document ->
                        Surface(onClick = {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", document)
                            val mime = when (document.extension.lowercase()) {
                                "pdf" -> "application/pdf"
                                "doc" -> "application/msword"
                                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                else -> "application/octet-stream"
                            }
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }.onFailure { error = it.message }
                        }, shape = RoundedCornerShape(10.dp), color = Color(0xFFEAF3EB),
                            modifier = Modifier.size(92.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = darkGreen)
                                Text(document.extension.uppercase(), color = darkGreen)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
            DraftActionButton("Сохранить", Icons.Default.Save, darkGreen) { save() }
            DraftActionButton("Поделиться", Icons.Default.Share, darkGreen, onClick = {
                save()
                val files = store.attachments(groupId, current.orderId) + store.documents(groupId, current.orderId)
                val uris = ArrayList<Uri>(files.map {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", it)
                })
                val intent = Intent(if (uris.isEmpty()) Intent.ACTION_SENDTO else Intent.ACTION_SEND_MULTIPLE).apply {
                    if (uris.isEmpty()) {
                        data = Uri.parse("mailto:")
                    } else {
                        type = "*/*"
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
            })
            DraftActionButton("Скачать", Icons.Default.FileDownload, darkGreen, onClick = {
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
            })
            DraftActionButton("Обновить", Icons.Default.Refresh, darkGreen) { confirmRefresh = true }
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
                                drafts = store.list(groupId).filter { it.orderId == orderId }
                                draftVersion++
                                selected = orderId
                            } catch (e: Exception) { error = e.message }
                        }
                    }) { Text("Обновить") } },
                    dismissButton = { TextButton(onClick = { confirmRefresh = false }) { Text("Отмена") } })
            }
        }
    }
    enlargedPhoto?.let { photo ->
        Dialog(onDismissRequest = { enlargedPhoto = null }) {
            val bitmap = remember(photo.absolutePath, photo.lastModified()) {
                runCatching { BitmapFactory.decodeFile(photo.absolutePath)?.asImageBitmap() }.getOrNull()
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap = bitmap, contentDescription = "Увеличенное фото",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp), contentScale = ContentScale.Fit)
                else Text("Не удалось открыть фотографию")
            }
        }
    }
}

@Composable
private fun DraftActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.material3.Surface(onClick = onClick,
            shape = RoundedCornerShape(14.dp), color = Color(0xFFEAF3EB),
            modifier = Modifier.size(width = 68.dp, height = 58.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(29.dp))
            }
        }
        Text(label, fontSize = 12.sp, color = Color(0xFF666666), maxLines = 1)
    }
}
