package ru.liftzip.orders.app.feature.mail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import ru.liftzip.orders.domain.model.OrderDetails
import ru.liftzip.orders.domain.model.OrderMailDraftFactory
import java.io.File
import java.util.UUID

/** Private, persistent draft snapshots. Original order photos are never modified. */
class GroupDraftStore(private val context: Context) {
    private val root = File(context.filesDir, "mail_drafts").apply { mkdirs() }

    data class Draft(val orderId: UUID, val subject: String, val recipient: String, val body: String, val count: Int)

    private fun folder(groupId: UUID) = File(root, groupId.toString())
    private fun file(groupId: UUID, orderId: UUID) = File(folder(groupId), "$orderId.json")

    fun list(groupId: UUID): List<Draft> =
        folder(groupId).listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            runCatching { parse(f.readText()) }.getOrNull()
        }?.sortedBy { it.subject } ?: emptyList()

    fun get(groupId: UUID, orderId: UUID): Draft? =
        file(groupId, orderId).takeIf { it.isFile }?.let { parse(it.readText()) }

    fun createIfAbsent(details: OrderDetails) {
        val order = details.order
        if (file(order.groupId, order.id).exists()) return
        refresh(details)
    }

    @Synchronized
    fun refresh(details: OrderDetails, preserveEdits: Boolean = false) {
        val draft = OrderMailDraftFactory.create(details)
        val target = file(draft.groupId, draft.orderId)
        val old = target.takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        val previous = attachmentFolder(draft.groupId, draft.orderId)
        val generation = "${draft.orderId}-${UUID.randomUUID()}"
        val staged = File(folder(draft.groupId), generation)
        check(staged.mkdirs()) { "Не удалось подготовить вложения письма" }
        try {
            draft.attachments.forEachIndexed { index, attachment ->
                val line = details.lines.first { it.id == attachment.lineId }
                val bytes = line.images[attachment.imageIndex]
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Не удалось прочитать фотографию позиции ${attachment.positionNumber}")
                val output = numberedImage(bitmap, attachment.positionNumber)
                File(staged, "${index + 1}.jpg").outputStream().use {
                    output.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
                output.recycle()
                bitmap.recycle()
            }
            val documentStore = OrderDocumentStore(context)
            var documentNumber = 0
            details.lines.forEachIndexed { lineIndex, line ->
                documentStore.lineFiles(draft.orderId, line.id).forEach { document ->
                    documentNumber++
                    document.copyTo(File(staged, "doc_${documentNumber}_item${lineIndex + 1}_${document.name.substringAfter('_')}"), overwrite = true)
                }
            }
            // Legacy order-level documents remain on disk for manual reassignment.
            // They must not reappear in a refreshed product-specific mail draft.
            val metadata = JSONObject()
                .put("orderId", draft.orderId.toString())
                .put("subject", if (preserveEdits) old?.optString("subject") ?: draft.subject else draft.subject)
                .put("recipient", old?.optString("recipient") ?: draft.recipient)
                .put("body", if (preserveEdits) old?.optString("body") ?: draft.body else draft.body)
                .put("count", draft.attachments.size)
                .put("generation", generation)
            atomicWrite(target, metadata.toString())
        } catch (error: Exception) {
            staged.deleteRecursively()
            throw error
        }
        if (previous != staged) previous.deleteRecursively()
    }

    @Synchronized
    fun update(groupId: UUID, orderId: UUID, recipient: String, subject: String, body: String) {
        val target = file(groupId, orderId)
        val old = JSONObject(target.readText())
        old.put("recipient", recipient).put("subject", subject).put("body", body)
        atomicWrite(target, old.toString())
    }

    private fun attachmentFolder(groupId: UUID, orderId: UUID): File {
        val metadata = file(groupId, orderId)
        val generation = if (metadata.isFile) JSONObject(metadata.readText()).optString("generation") else ""
        val suffix = generation.removePrefix("$orderId-")
        return File(folder(groupId), if (generation.startsWith("$orderId-") &&
            runCatching { UUID.fromString(suffix).toString() == suffix }.getOrDefault(false)
        ) generation else orderId.toString())
    }

    fun attachments(groupId: UUID, orderId: UUID): List<File> =
        attachmentFolder(groupId, orderId).listFiles { f -> f.extension == "jpg" }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE } ?: emptyList()

    fun documents(groupId: UUID, orderId: UUID): List<File> =
        attachmentFolder(groupId, orderId).listFiles { f -> f.isFile && f.name.startsWith("doc_") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun atomicWrite(target: File, value: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(value)
        check(temp.renameTo(target)) { "Не удалось сохранить черновик" }
    }

    private fun parse(json: String): Draft {
        val obj = JSONObject(json)
        return Draft(UUID.fromString(obj.getString("orderId")), obj.getString("subject"),
            obj.optString("recipient"), obj.getString("body"), obj.optInt("count"))
    }

    private fun numberedImage(source: Bitmap, number: Int): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val scale = (minOf(bitmap.width, bitmap.height) / 700f).coerceAtLeast(0.5f)
        val size = 58f * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = number.toString()
        paint.textSize = size
        paint.isFakeBoldText = true
        val width = paint.measureText(label) + 32f * scale
        paint.color = Color.argb(205, 20, 20, 20)
        canvas.drawRoundRect(RectF(12f * scale, 12f * scale, 12f * scale + width,
            12f * scale + 82f * scale), 14f * scale, 14f * scale, paint)
        paint.color = Color.WHITE
        canvas.drawText(label, 28f * scale, 12f * scale + 61f * scale, paint)
        return bitmap
    }
}
