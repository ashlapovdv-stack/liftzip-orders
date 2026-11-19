package ru.liftzip.orders.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    @Test fun sendsAuthenticatedArchiveAndChecksMetadataWhenUploadOmitsChecksums() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = File.createTempFile("cloud-transport-", ".zip", context.cacheDir)
        try {
            val bytes = ByteArray(300_000) { (it % 237).toByte() }
            archive.writeBytes(bytes)
            val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files?upload_id=test"
            val checksum = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            val connections = mutableListOf<DriveConnection>()
            val api = DriveBackupApi("test-token") { url ->
                when (connections.size) {
                    0 -> DriveConnection(url, "", uploadUrl)
                    1 -> DriveConnection(url, """{"id":"uploaded-id"}""")
                    2 -> DriveConnection(url, """{"id":"uploaded-id","size":"${bytes.size}","md5Checksum":"$checksum"}""")
                    else -> error("Unexpected request")
                }.also { connections.add(it) }
            }

            api.upload("parent-id", "test.orders.zip", archive)

            val (start, put, info) = connections
            assertEquals("POST", start.requestMethod)
            assertEquals("Bearer test-token", start.getRequestProperty("Authorization"))
            assertEquals(bytes.size.toString(), start.getRequestProperty("X-Upload-Content-Length"))
            assertEquals("parent-id", JSONObject(start.body.toString("UTF-8")).getJSONArray("parents").getString(0))
            assertEquals("PUT", put.requestMethod)
            assertEquals("Bearer test-token", put.getRequestProperty("Authorization"))
            assertEquals("bytes 0-${bytes.size - 1}/${bytes.size}", put.getRequestProperty("Content-Range"))
            assertArrayEquals(bytes, put.body.toByteArray())
            assertEquals("GET", info.requestMethod)
            assertEquals("/drive/v3/files/uploaded-id", info.url.path)
        } finally {
            archive.delete()
        }
    }

    private class DriveConnection(
        url: URL, private val response: String, private val location: String? = null,
    ) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = HTTP_OK
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = response.byteInputStream()
        override fun getOutputStream() = body
    }
}
