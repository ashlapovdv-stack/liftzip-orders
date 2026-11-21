package ru.liftzip.orders.app.feature.diagnostics

import android.content.Context
import java.io.File
import java.time.Instant

/** Opt-in local invoice OCR diagnostics. Never transmitted automatically. */
object InvoiceDiagnostics {
    private const val PREFS = "invoice_diagnostics"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_TEXT = 24_000
    private const val MAX_LOG = 120_000

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, value).apply()
    }

    private fun file(context: Context) = File(context.filesDir, "invoice-diagnostics.txt")

    @Synchronized fun record(context: Context, stage: String, detail: String) {
        if (!enabled(context)) return
        val safeStage = stage.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val entry = "${Instant.now()} | $safeStage\n${detail.take(MAX_TEXT)}\n\n"
        val target = file(context)
        val previous = if (target.exists()) target.readText().takeLast(MAX_LOG - entry.length.coerceAtMost(MAX_LOG)) else ""
        target.writeText((previous + entry).takeLast(MAX_LOG))
    }

    fun read(context: Context): String = if (file(context).exists()) file(context).readText() else
        "Диагностических записей пока нет. Включите диагностику и повторите импорт PDF."

    fun clear(context: Context) { file(context).delete() }
}
