package ru.liftzip.orders.app.feature.mail

import android.content.Context
import android.util.Base64
import android.util.Base64OutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

object EmlExporter {
    fun export(
        context: Context, groupId: UUID, orderId: UUID, store: GroupDraftStore,
        subject: String, recipient: String, body: String,
    ): File {
        val dir = File(context.cacheDir, "shared_letters").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.eml")
        val boundary = "orders-${UUID.randomUUID()}"
        val subjectText = Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        try {
            file.outputStream().buffered().use { out ->
                fun text(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
                fun contents(input: InputStream) {
                    input.use {
                        Base64OutputStream(out, Base64.NO_CLOSE or Base64.CRLF).use { encoded -> it.copyTo(encoded) }
                    }
                    text("\r\n")
                }
                fun attachment(source: File, name: String, mime: String) {
                    text("--$boundary\r\nContent-Type: $mime; name=\"$name\"\r\n")
                    text("Content-Disposition: attachment; filename=\"$name\"\r\n")
                    text("Content-Transfer-Encoding: base64\r\n\r\n")
                    contents(source.inputStream())
                }

                text("MIME-Version: 1.0\r\n")
                text("Subject: =?UTF-8?B?$subjectText?=\r\n")
                if (recipient.isNotBlank()) text("To: ${recipient.replace("\r", "").replace("\n", "")}\r\n")
                text("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
                text("--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\n")
                text("Content-Transfer-Encoding: base64\r\n\r\n")
                contents(body.byteInputStream())
                store.attachments(groupId, orderId).forEachIndexed { index, photo ->
                    attachment(photo, "photo-${index + 1}.jpg", "image/jpeg")
                }
                store.documents(groupId, orderId).forEach { document ->
                    val name = document.name.substringAfter("doc_").substringAfter('_')
                        .replace('"', '_').replace('\r', '_').replace('\n', '_')
                    val mime = when (document.extension.lowercase()) {
                        "pdf" -> "application/pdf"
                        "doc" -> "application/msword"
                        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        else -> "application/octet-stream"
                    }
                    attachment(document, name, mime)
                }
                text("--$boundary--\r\n")
            }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
