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
import java.io.EOFException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DriveUploadTest {
    @Test fun probesAfterLostResponseAndResumesFromTheAcknowledgedByte() = withArchive(600_000) { archive, bytes ->
        val checksum = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        val connections = mutableListOf<DriveConnection>()
        val api = DriveBackupApi("test-token") { url ->
            when (connections.size) {
                0 -> DriveConnection(url, headers = mapOf("Location" to SESSION))
                1 -> DriveConnection(url, loseResponse = true)
                2 -> DriveConnection(url, status = 308, headers = mapOf("Range" to "bytes=0-131071"))
                3 -> DriveConnection(url, status = 308, headers = mapOf("Range" to "bytes=0-393215"))
                4 -> DriveConnection(url, status = 201, response = """{"id":"uploaded-id"}""")
                5 -> DriveConnection(url, response = """{"id":"uploaded-id","size":"600000","md5Checksum":"$checksum"}""")
                else -> error("Unexpected request")
            }.also { connections.add(it) }
        }

        api.upload("parent-id", "test.orders.zip", archive)

        val start = connections.first()
        assertEquals("POST", start.requestMethod)
        assertTrue(start.url.query.startsWith("uploadType=resumable&"))
        assertEquals("600000", start.getRequestProperty("X-Upload-Content-Length"))
        val metadata = JSONObject(start.body.toString("UTF-8"))
        assertEquals("test.orders.zip", metadata.getString("name"))
        assertEquals("parent-id", metadata.getJSONArray("parents").getString(0))
        assertChunk(connections[1], "bytes 0-262143/600000", bytes.copyOfRange(0, 262144))
        assertChunk(connections[2], "bytes */600000", byteArrayOf())
        assertChunk(connections[3], "bytes 131072-393215/600000", bytes.copyOfRange(131072, 393216))
        assertChunk(connections[4], "bytes 393216-599999/600000", bytes.copyOfRange(393216, 600000))
        assertEquals("GET", connections[5].requestMethod)
        assertEquals("/drive/v3/files/uploaded-id", connections[5].url.path)
    }

    @Test fun acceptsVerifiedCompletionAfterTheFinalResponseWasLost() = withArchive(3) { archive, bytes ->
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val connections = mutableListOf<DriveConnection>()
        val api = DriveBackupApi("test-token") { url ->
            when (connections.size) {
                0 -> DriveConnection(url, headers = mapOf("Location" to SESSION))
                1 -> DriveConnection(url, loseResponse = true)
                2 -> DriveConnection(url, response = """{"id":"uploaded-id","size":"3","sha256Checksum":"$checksum"}""")
                else -> error("Unexpected request")
            }.also { connections.add(it) }
        }

        api.upload("parent-id", "test.orders.zip", archive)

        assertEquals(3, connections.size)
        assertChunk(connections[1], "bytes 0-2/3", bytes)
        assertChunk(connections[2], "bytes */3", byteArrayOf())
    }

    private fun assertChunk(connection: DriveConnection, range: String, body: ByteArray) {
        assertEquals(SESSION, connection.url.toString())
        assertEquals("PUT", connection.requestMethod)
        assertEquals("Bearer test-token", connection.getRequestProperty("Authorization"))
        assertEquals(range, connection.getRequestProperty("Content-Range"))
        assertArrayEquals(body, connection.body.toByteArray())
    }

    private fun withArchive(size: Int, test: (File, ByteArray) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = File.createTempFile("cloud-transport-", ".zip", context.cacheDir)
        try {
            val bytes = ByteArray(size) { (it % 237).toByte() }
            archive.writeBytes(bytes)
            test(archive, bytes)
        } finally {
            archive.delete()
        }
    }

    private class DriveConnection(
        url: URL,
        private val status: Int = HTTP_OK,
        private val response: String = "",
        private val headers: Map<String, String> = emptyMap(),
        private val loseResponse: Boolean = false,
    ) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int = if (loseResponse) throw EOFException() else status
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getInputStream() = response.byteInputStream()
        override fun getOutputStream() = body
    }

    private companion object {
        const val SESSION = "https://www.googleapis.com/upload/drive/v3/files?upload_id=fixture"
    }
}
