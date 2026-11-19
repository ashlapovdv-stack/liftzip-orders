package ru.liftzip.orders.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.settings.DriveBackupApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DriveUploadTest {
    @Test fun sendsMultipartArchiveAndChecksMetadataWhenUploadOmitsChecksums() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = File.createTempFile("cloud-transport-", ".zip", context.cacheDir)
        try {
            val bytes = ByteArray(300_000) { (it % 237).toByte() }
            archive.writeBytes(bytes)
            val checksum = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            val connections = mutableListOf<DriveConnection>()
            val api = DriveBackupApi("test-token") { url ->
                when (connections.size) {
                    0 -> DriveConnection(url, """{"id":"uploaded-id"}""")
                    1 -> DriveConnection(url, """{"id":"uploaded-id","size":"${bytes.size}","md5Checksum":"$checksum"}""")
                    else -> error("Unexpected request")
                }.also { connections.add(it) }
            }

            api.upload("parent-id", "test.orders.zip", archive)

            val (post, info) = connections
            assertEquals("POST", post.requestMethod)
            assertEquals("Bearer test-token", post.getRequestProperty("Authorization"))
            assertTrue(post.url.query.startsWith("uploadType=multipart&"))
            val type = post.getRequestProperty("Content-Type")
            assertTrue(type.startsWith("multipart/related; boundary="))
            val boundary = type.substringAfter("boundary=")
            val parts = post.body.toString("ISO-8859-1").split("--$boundary")
            assertEquals("", parts[0])
            val metadata = JSONObject(parts[1].substringAfter("\r\n\r\n").trim())
            assertEquals("parent-id", metadata.getJSONArray("parents").getString(0))
            assertEquals("test.orders.zip", metadata.getString("name"))
            assertTrue(parts[2].startsWith("\r\nContent-Type: application/zip\r\n\r\n"))
            val sentArchive = parts[2].substringAfter("\r\n\r\n").removeSuffix("\r\n")
            assertArrayEquals(bytes, sentArchive.toByteArray(Charsets.ISO_8859_1))
            assertEquals("--\r\n", parts[3])
            assertEquals(post.body.size().toLong(), post.declaredLength())
            assertEquals("GET", info.requestMethod)
            assertEquals("/drive/v3/files/uploaded-id", info.url.path)
        } finally {
            archive.delete()
        }
    }

    @Test fun distinguishesWritingTheBodyFromReadingTheResponse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = File.createTempFile("cloud-transport-", ".zip", context.cacheDir)
        try {
            archive.writeBytes(byteArrayOf(1, 2, 3))
            val failures = mapOf("передача архива" to true, "ответ на загрузку" to false)
            for ((stage, duringWrite) in failures) {
                val api = DriveBackupApi("test-token") { url ->
                    object : DriveConnection(url, "") {
                        override fun getOutputStream(): ByteArrayOutputStream =
                            if (duringWrite) throw java.io.IOException("unexpected end of stream") else super.getOutputStream()
                        override fun getResponseCode(): Int = throw java.io.EOFException()
                    }
                }
                val failure = runCatching { api.upload("parent-id", "test.orders.zip", archive) }.exceptionOrNull()
                assertEquals(stage, failure?.message)
                assertTrue(failure?.cause is java.io.IOException)
            }
        } finally {
            archive.delete()
        }
    }

    private open class DriveConnection(
        url: URL, private val response: String,
    ) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = HTTP_OK
        fun declaredLength(): Long = fixedContentLengthLong
        override fun getInputStream() = response.byteInputStream()
        override fun getOutputStream() = body
    }
}
