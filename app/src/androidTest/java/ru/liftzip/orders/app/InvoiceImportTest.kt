package ru.liftzip.orders.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.diagnostics.InvoiceDiagnostics
import ru.liftzip.orders.app.feature.orders.InvoicePdfReader

@RunWith(AndroidJUnit4::class)
class InvoiceImportTest {
    @Test fun readsQuantitiesFromPdfAndGalleryWithVisibleQuantityColumn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdf = File.createTempFile("invoice-test", ".pdf", context.cacheDir)
        val image = File.createTempFile("invoice-test", ".png", context.cacheDir)
        val previousDiagnostics = InvoiceDiagnostics.enabled(context)
        InvoiceDiagnostics.setEnabled(context, true)
        try {
            InvoiceTestFixture.writePdf(pdf)
            InvoiceTestFixture.writeImage(image)

            val reader = InvoicePdfReader()
            val fromPdf = reader.read(context, context.contentResolver, Uri.fromFile(pdf))
            val fromGallery = reader.readImage(context, context.contentResolver, Uri.fromFile(image))
            assertEquals("Import from PDF\n${InvoiceDiagnostics.read(context)}", listOf("5", "8"), fromPdf.map { it.quantity })
            assertEquals("Import from gallery\n${InvoiceDiagnostics.read(context)}", listOf("5", "8"), fromGallery.map { it.quantity })
        } finally {
            InvoiceDiagnostics.clear(context)
            InvoiceDiagnostics.setEnabled(context, previousDiagnostics)
            pdf.delete()
            image.delete()
        }
    }
}
