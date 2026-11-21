package ru.liftzip.orders.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** A synthetic invoice shared by the debug and release import tests. */
final class InvoiceTestFixture {
    private InvoiceTestFixture() {}

    static void writePdf(File file) throws IOException {
        PdfDocument pdf = new PdfDocument();
        try (FileOutputStream output = new FileOutputStream(file)) {
            PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(600, 360, 1).create());
            draw(page.getCanvas());
            pdf.finishPage(page);
            pdf.writeTo(output);
        } finally {
            pdf.close();
        }
    }

    static void writeImage(File file) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(1200, 720, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(file)) {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(2f, 2f);
            draw(canvas);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            bitmap.recycle();
        }
    }

    private static void draw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(Color.BLACK);
        ink.setStrokeWidth(1.5f);
        ink.setTextSize(14f);
        ink.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        for (float y : new float[]{115, 142, 170, 198}) canvas.drawLine(36, y, 558, y, ink);
        for (float x : new float[]{36, 68, 318, 382, 428, 485, 558}) canvas.drawLine(x, 115, x, 198, ink);

        canvas.drawText("Счёт на оплату", 36, 80, ink);
        canvas.drawText("№", 45, 133, ink);
        canvas.drawText("Товары", 171, 133, ink);
        canvas.drawText("Кол-во", 326, 133, ink);
        canvas.drawText("Ед.", 389, 133, ink);
        canvas.drawText("Цена", 435, 133, ink);
        canvas.drawText("Сумма", 496, 133, ink);
        String[] names = {"Датчик A123", "Катушка B890"};
        String[] quantities = {"5", "8"};
        String[] totals = {"500,00", "800,00"};
        for (int i = 0; i < names.length; i++) {
            int y = 161 + 28 * i;
            canvas.drawText(Integer.toString(i + 1), 48, y, ink);
            canvas.drawText(names[i], 74, y, ink);
            canvas.drawText(quantities[i], 373, y, ink);
            canvas.drawText("шт", 384, y, ink);
            canvas.drawText("100,00", 433, y, ink);
            canvas.drawText(totals[i], 500, y, ink);
        }
        canvas.drawText("Всего наименований 2", 36, 245, ink);
    }
}
