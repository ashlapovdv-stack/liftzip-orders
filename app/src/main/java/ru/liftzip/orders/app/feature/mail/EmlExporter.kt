package ru.liftzip.orders.app.feature.mail

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.UUID

object EmlExporter {
    fun export(
        context: Context, groupId: UUID, orderId: UUID, store: GroupDraftStore,
        subject: String, recipient: String, body: String,
    ): File {
        val dir = File(context.cacheDir, "shared_letters").apply { mkdirs() }
        val file = File(dir, "$orderId.eml")
        val boundary = "orders-${UUID.randomUUID()}"
        fun encoded(value: String): String =
            "=?UTF-8?B?${Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.append("MIME-Version: 1.0\r\n")
            out.append("Subject: ${encoded(subject)}\r\n")
            if (recipient.isNotBlank()) out.append("To: ${recipient.replace("\r", "").replace("\n", "")}\r\n")
            out.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
            out.append("--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\n")
            out.append("Content-Transfer-Encoding: base64\r\n\r\n")
            out.append(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.CRLF))
            out.append("\r\n")
            store.attachments(groupId, orderId).forEachIndexed { index, photo ->
                val name = "photo-${index + 1}.jpg"
                out.append("--$boundary\r\nContent-Type: image/jpeg; name=\"$name\"\r\n")
                out.append("Content-Disposition: attachment; filename=\"$name\"\r\n")
                out.append("Content-Transfer-Encoding: base64\r\n\r\n")
                out.append(Base64.encodeToString(photo.readBytes(), Base64.CRLF))
                out.append("\r\n")
            }
            out.append("--$boundary--\r\n")
        }
        return file
    }
}
