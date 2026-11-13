package ru.liftzip.orders.app.feature.mail

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Persistent order-owned documents, separate from generated mail snapshots. */
class OrderDocumentStore(private val context: Context) {
    private val root = File(context.filesDir, "order_documents").apply { mkdirs() }
    private fun dir(orderId: UUID) = File(root, orderId.toString()).apply { mkdirs() }

    fun files(orderId: UUID): List<File> =
        dir(orderId).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    private fun lineDir(orderId: UUID, lineId: UUID) =
        File(dir(orderId), lineId.toString()).apply { mkdirs() }

    fun lineFiles(orderId: UUID, lineId: UUID): List<File> =
        lineDir(orderId, lineId).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun delete(orderId: UUID, lineId: UUID, file: File): Boolean {
        val directory = lineDir(orderId, lineId).canonicalFile
        return file.canonicalFile.parentFile == directory && file.delete()
    }

    fun addToLine(orderId: UUID, lineId: UUID, uri: Uri): File = addToDirectory(lineDir(orderId, lineId), uri)

    fun add(orderId: UUID, uri: Uri): File = addToDirectory(dir(orderId), uri)

    private fun addToDirectory(directory: File, uri: Uri): File {
        val resolver = context.contentResolver
        val suppliedName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "document"
        val clean = suppliedName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(90).ifBlank { "document" }
        val target = File(directory, "${UUID.randomUUID()}_$clean")
        try {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: error("Не удалось открыть документ")
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return target
    }
}
