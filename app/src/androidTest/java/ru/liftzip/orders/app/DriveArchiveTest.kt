package ru.liftzip.orders.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.settings.DriveArchive
import ru.liftzip.orders.app.feature.settings.DriveRecoveryCode
import java.io.File

@RunWith(AndroidJUnit4::class)
class DriveArchiveTest {
    @Test fun archiveUsesTheRecoveryCodeAndRoundTripsWithoutDeviceCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = File.createTempFile("orders-test-", ".zip", context.cacheDir)
        val cloud = File.createTempFile("orders-cloud-test-", ".zip", context.cacheDir)
        try {
            val data = ByteArray(140_000) { index -> (index % 223).toByte() }
            input.writeBytes(data)
            val code = DriveArchive.newCode()
            assertEquals(code, DriveRecoveryCode.unseal(DriveRecoveryCode.seal(code)))
            DriveArchive.encrypt(context, Uri.fromFile(input), cloud, code)
            assertFalse(data.contentEquals(cloud.readBytes()))
            assertTrue(runCatching {
                DriveArchive.decrypt(context, Uri.fromFile(cloud), DriveArchive.newCode()).delete()
            }.isFailure)
            val retrieved = DriveArchive.decrypt(context, Uri.fromFile(cloud), code)
            try { assertArrayEquals(data, retrieved.readBytes()) } finally { retrieved.delete() }
        } finally {
            input.delete()
            cloud.delete()
        }
    }
}
