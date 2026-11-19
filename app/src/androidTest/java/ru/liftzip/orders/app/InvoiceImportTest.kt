package ru.liftzip.orders.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 360, 1).create())
                drawInvoice(page.canvas)
                document.finishPage(page)
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val bitmap = Bitmap.createBitmap(1200, 720, Bitmap.Config.ARGB_8888)
            try {
                drawInvoice(Canvas(bitmap).apply { scale(2f, 2f) })
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }

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

    private fun drawInvoice(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 1.5f
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        for (y in listOf(115f, 142f, 170f, 198f)) canvas.drawLine(36f, y, 558f, y, ink)
        for (x in listOf(36f, 68f, 318f, 382f, 428f, 485f, 558f)) canvas.drawLine(x, 115f, x, 198f, ink)

        canvas.drawText("Счёт на оплату", 36f, 80f, ink)
        canvas.drawText("№", 45f, 133f, ink)
        canvas.drawText("Товары", 171f, 133f, ink)
        canvas.drawText("Кол-во", 326f, 133f, ink)
        canvas.drawText("Ед.", 389f, 133f, ink)
        canvas.drawText("Цена", 435f, 133f, ink)
        canvas.drawText("Сумма", 496f, 133f, ink)
        for ((index, y) in listOf(161f, 189f).withIndex()) {
            canvas.drawText("${index + 1}", 48f, y, ink)
            canvas.drawText(listOf("Датчик A123", "Катушка B890")[index], 74f, y, ink)
            canvas.drawText(listOf("5", "8")[index], 373f, y, ink)
            canvas.drawText("шт", 384f, y, ink)
            canvas.drawText("100,00", 433f, y, ink)
            canvas.drawText(listOf("500,00", "800,00")[index], 500f, y, ink)
        }
        canvas.drawText("Всего наименований 2", 36f, 245f, ink)
    }
}
