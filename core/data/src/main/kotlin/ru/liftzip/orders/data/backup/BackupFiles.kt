package ru.liftzip.orders.data.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

internal val backupRoots = listOf("order_documents", "mail_drafts")

internal fun isBackupMediaPath(path: String): Boolean {
    val parts = path.split('/')
    return parts.size in 2..4 && parts.first() in (backupRoots + "photos") &&
        parts.drop(1).all { it.isNotBlank() && it !in listOf(".", "..") && '\\' !in it && '\u0000' !in it }
}

internal fun copyBackupFile(
    input: InputStream,
    output: OutputStream,
    path: String,
    maxSize: Long = 512L * 1024 * 1024,
): BackupMedia {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        size += count
        if (size > maxSize) throw BackupException("Файл в архиве превышает допустимый размер")
        hash.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    return BackupMedia(path, hash.digest().joinToString("") { "%02x".format(it) }, size)
}

internal fun backupJournal(filesDir: File, id: String): File =
    File(filesDir, "backup-restore/${UUID.fromString(id)}")

/** Durable staging stays available until the database's pending marker is cleared. */
internal fun installBackupFiles(filesDir: File, journal: File) {
    for (name in backupRoots) {
        val stage = File(journal, "incoming/$name")
        val live = File(filesDir, name)
        val previous = File(journal, "previous/$name")
        if (!stage.exists()) {
            check(live.isDirectory) { "Не найдены восстановленные вложения" }
            continue
        }
        if (live.exists() && !previous.exists()) {
            check(previous.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
            check(live.renameTo(previous)) { "Не удалось сохранить предыдущие вложения" }
        }
        check(!live.exists() && stage.renameTo(live)) { "Не удалось восстановить вложения" }
    }
}
