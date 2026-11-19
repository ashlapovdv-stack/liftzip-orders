package ru.liftzip.orders.app.feature.settings

import android.accounts.Account
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.liftzip.orders.app.OrdersApplication
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val DRIVE_FOLDER = "application/vnd.google-apps.folder"
private const val API = "https://www.googleapis.com/drive/v3"
private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
private const val ZIP = "application/zip"
private const val CHUNK_BYTES = 256 * 1024
private const val RESUME_INCOMPLETE = 308
private const val FILE_FIELDS = "id,size,sha256Checksum,md5Checksum"
private const val DRIVE_JOB = "orders-upload-backup"
private val Context.driveBackupStore by preferencesDataStore("drive_backup")

internal fun driveAuthorization(email: String? = null): AuthorizationRequest =
    AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).apply {
        if (email != null) setAccount(Account(email, "com.google"))
    }.build()

internal data class DriveBackupSettings(
    val email: String? = null,
    val sealedCode: String? = null,
    val lastUpload: Long? = null,
    val lastFile: String? = null,
    val error: String? = null,
)

internal class DriveBackupStore(context: Context) {
    private val data = context.applicationContext.driveBackupStore

    val settings = data.data.map {
        DriveBackupSettings(it[emailKey], it[codeKey], it[uploadedKey], it[fileKey], it[errorKey])
    }

    suspend fun connect(email: String, code: String) {
        val sealed = DriveRecoveryCode.seal(code)
        data.edit {
            it.clear()
            it[emailKey] = email
            it[codeKey] = sealed
        }
    }

    suspend fun disconnect() { data.edit { it.clear() } }

    suspend fun record(sealedCode: String, name: String? = null, error: String? = null) {
        data.edit {
            if (it[codeKey] != sealedCode) return@edit
            if (name != null) {
                it[fileKey] = name
                it[uploadedKey] = System.currentTimeMillis()
            }
            if (error == null) it.remove(errorKey) else it[errorKey] = error
        }
    }

    private companion object {
        val emailKey = stringPreferencesKey("email")
        val codeKey = stringPreferencesKey("code")
        val uploadedKey = longPreferencesKey("uploaded")
        val fileKey = stringPreferencesKey("file")
        val errorKey = stringPreferencesKey("error")
    }
}

internal object DriveBackupSchedule {
    fun enqueue(context: Context) {
        enqueue(WorkManager.getInstance(context), ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun observe(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(DRIVE_JOB)

    suspend fun retryNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (observe(context).first().any { it.state == WorkInfo.State.RUNNING }) return
        enqueue(manager, ExistingWorkPolicy.REPLACE).await()
    }

    private fun enqueue(manager: WorkManager, policy: ExistingWorkPolicy) =
        manager.enqueueUniqueWork(
            DRIVE_JOB, policy,
            OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )

    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(DRIVE_JOB) }
}

class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        uploadLock.withLock { uploadLatest() }
    }

    private suspend fun uploadLatest(): Result {
        val app = applicationContext as OrdersApplication
        val store = DriveBackupStore(app)
        val state = store.settings.first()
        val email = state.email ?: return Result.success()
        val sealed = state.sealedCode ?: return Result.success()
        val uri = app.settings.localBackup.first().folder ?: return Result.success()
        val archive = latestDailyBackup(DocumentFile.fromTreeUri(app, uri))
        val name = archive?.name
        if (archive == null || name == null) {
            store.record(sealed, error = "В выбранной папке нет локальной автокопии для отправки.")
            return Result.success()
        }
        if (state.lastFile == name) {
            if (state.error != null) store.record(sealed)
            return Result.success()
        }

        var stage = "доступ к Google Диску"
        return try {
            val result = Tasks.await(Identity.getAuthorizationClient(app).authorize(driveAuthorization(email)), 30, TimeUnit.SECONDS)
            val token = result.accessToken
            if (result.hasResolution() || token.isNullOrBlank()) {
                store.record(sealed, error = "Откройте настройки и обновите разрешение Google Диска.")
                return Result.success()
            }
            val drive = DriveBackupApi(token)
            val fileName = name.removeSuffix(".zip") + ".orders.zip"
            stage = "проверка папки и копий"
            val folder = drive.backupFolder()
            if (!drive.contains(folder, fileName)) {
                val encrypted = File.createTempFile("orders-upload-", ".zip", app.cacheDir)
                try {
                    stage = "шифрование архива"
                    DriveArchive.encrypt(app, archive.uri, encrypted, DriveRecoveryCode.unseal(sealed))
                    currentCoroutineContext().ensureActive()
                    if (store.settings.first().sealedCode != sealed) return Result.success()
                    stage = "отправка архива"
                    val coroutine = currentCoroutineContext()
                    drive.upload(folder, fileName, encrypted) { coroutine.ensureActive() }
                } finally {
                    encrypted.delete()
                }
            }
            currentCoroutineContext().ensureActive()
            store.record(sealed, name)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val remote = error as? DriveResponseError
            val permanent = remote?.status in setOf(400, 401, 403)
            val detail = when (error) {
                is DriveResponseError -> "${error.step}, HTTP ${error.status}"
                is DriveUploadError -> listOfNotNull(error.step, error.cause?.transportDetail()).joinToString(", ")
                is java.net.SocketTimeoutException -> "$stage, истекло время ожидания"
                is java.net.UnknownHostException -> "$stage, сервер недоступен"
                else -> "$stage, ${error.javaClass.simpleName}"
            }
            store.record(sealed, error = "Ошибка облачной копии ($detail). Локальная копия сохранена.")
            if (permanent || runAttemptCount >= 4) Result.success() else Result.retry()
        }
    }

    private companion object {
        val uploadLock = Mutex()
    }
}

private class DriveResponseError(val status: Int, val step: String) : IOException("Drive HTTP $status")
private class DriveUploadError(val step: String, cause: Throwable? = null) : IOException(step, cause)

private fun Throwable.transportDetail(): String = when (this) {
    is java.net.SocketTimeoutException -> "истекло время ожидания"
    is java.net.UnknownHostException -> "сервер недоступен"
    is javax.net.ssl.SSLException -> "сбой защищённого соединения"
    is java.io.EOFException -> "сервер закрыл соединение"
    is java.net.ProtocolException -> "ошибка протокола HTTP"
    is java.net.SocketException -> "сетевое соединение прервано"
    else -> {
        val detail = message.orEmpty()
        when {
            detail.contains("unexpected end of stream", ignoreCase = true) -> "сервер закрыл соединение"
            detail.contains("connection reset", ignoreCase = true) -> "соединение принудительно закрыто"
            detail.contains("broken pipe", ignoreCase = true) -> "соединение прервалось при записи"
            detail.contains("expected", ignoreCase = true) && detail.contains("bytes", ignoreCase = true) ->
                "размер переданных данных не совпал"
            else -> javaClass.simpleName
        }
    }
}

internal class DriveBackupApi(
    private val token: String,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun accountEmail(): String =
        request("$API/about?fields=user(emailAddress)").getJSONObject("user").getString("emailAddress")

    fun backupFolder(): String {
        val query = "trashed = false and mimeType = '$DRIVE_FOLDER' and appProperties has { key='ordersBackup' and value='1' }"
        return find(query) ?: request("$API/files?fields=id", "POST",
            JSONObject().put("name", "Резервные копии Заказов").put("mimeType", DRIVE_FOLDER)
                .put("appProperties", JSONObject().put("ordersBackup", "1"))).getString("id")
    }

    fun contains(folder: String, name: String): Boolean =
        find("trashed = false and '${escapeQuery(folder)}' in parents and name = '${escapeQuery(name)}' and mimeType = 'application/zip'") != null

    private fun find(query: String): String? =
        request("$API/files?fields=files(id)&pageSize=1&q=${URLEncoder.encode(query, "UTF-8")}")
            .getJSONArray("files").optJSONObject(0)?.optString("id")

    fun upload(folder: String, name: String, file: File, checkActive: () -> Unit = {}) {
        val length = file.length().also { require(it > 0) }
        val metadata = JSONObject().put("name", name).put("mimeType", ZIP)
            .put("parents", org.json.JSONArray(listOf(folder)))
        val session = startUpload(metadata, length)
        val saved = sendArchive(session, file, length, checkActive)
        val id = URLEncoder.encode(saved.getString("id"), "UTF-8")
        val info = if (saved.has("size") && (saved.optString("sha256Checksum").isNotBlank() ||
                saved.optString("md5Checksum").isNotBlank())
        ) saved else request("$API/files/$id?fields=$FILE_FIELDS")

        val remoteSha = info.optString("sha256Checksum")
        val (algorithm, expected) = if (remoteSha.isNotBlank()) "SHA-256" to remoteSha
            else "MD5" to info.optString("md5Checksum")
        if (info.optLong("size", -1) != length || !file.checksum(algorithm, checkActive).equals(expected, true)) {
            runCatching { request("$API/files/$id", "PATCH", JSONObject().put("trashed", true)) }
            throw DriveUploadError("контрольная сумма копии не совпала")
        }
    }

    private fun startUpload(metadata: JSONObject, length: Long): String {
        val request = open("$UPLOAD_API?uploadType=resumable&fields=$FILE_FIELDS", "POST")
        return try {
            transferStep("открытие сессии загрузки") {
                request.setRequestProperty("X-Upload-Content-Type", ZIP)
                request.setRequestProperty("X-Upload-Content-Length", length.toString())
                request.writeJson(metadata)
                request.checkResponse("открытие сессии загрузки")
                request.getHeaderField("Location") ?: throw IOException("Missing upload session")
            }
        } finally { request.disconnect() }
    }

    private sealed interface UploadProgress {
        data class Incomplete(val nextByte: Long) : UploadProgress
        data class Complete(val metadata: JSONObject) : UploadProgress
    }

    private fun sendArchive(session: String, file: File, length: Long, checkActive: () -> Unit): JSONObject {
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            var offset = 0L
            var stalled = 0
            while (offset < length) {
                checkActive()
                val count = minOf(buffer.size.toLong(), length - offset).toInt()
                input.seek(offset)
                input.readFully(buffer, 0, count)
                val end = offset + count
                val progress = try {
                    sendChunk(session, length, offset, buffer, count)
                } catch (error: IOException) {
                    if (error is DriveResponseError && error.status !in 500..599) throw error
                    checkActive()
                    sendChunk(session, length, 0, buffer, 0)
                }
                when (progress) {
                    is UploadProgress.Complete -> return progress.metadata
                    is UploadProgress.Incomplete -> {
                        val next = progress.nextByte
                        if (next < offset || next > end || next == length) {
                            throw DriveUploadError("Google Диск вернул неверную позицию загрузки")
                        }
                        stalled = if (next == offset) stalled + 1 else 0
                        if (stalled >= 3) throw DriveUploadError("Google Диск не подтвердил получение архива")
                        offset = next
                    }
                }
            }
        }
        throw DriveUploadError("Google Диск не подтвердил завершение загрузки")
    }

    private fun sendChunk(
        session: String, total: Long, offset: Long, buffer: ByteArray, count: Int,
    ): UploadProgress {
        val request = open(session, "PUT")
        return try {
            request.doOutput = true
            request.setRequestProperty("Content-Type", ZIP)
            val bytes = if (count == 0) "*" else "${offset}-${offset + count - 1}"
            request.setRequestProperty("Content-Range", "bytes $bytes/$total")
            request.setFixedLengthStreamingMode(count)
            transferStep(if (count == 0) "проверка принятой части архива" else "передача части архива") {
                request.outputStream.use { it.write(buffer, 0, count) }
                when (val status = request.responseCode) {
                    HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED -> UploadProgress.Complete(request.readJson())
                    RESUME_INCOMPLETE -> {
                        val range = request.getHeaderField("Range")
                        val last = range?.let { Regex("""bytes=0-(\d+)""").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
                        if (range != null && last == null) throw IOException("Invalid upload range")
                        UploadProgress.Incomplete(last?.plus(1) ?: 0)
                    }
                    else -> throw DriveResponseError(status, "приём архива Google Диском")
                }
            }
        } finally { request.disconnect() }
    }

    private fun File.checksum(algorithm: String, checkActive: () -> Unit): String {
        val digest = MessageDigest.getInstance(algorithm)
        inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> transferStep(step: String, block: () -> T): T = try {
        block()
    } catch (error: DriveResponseError) {
        throw error
    } catch (error: IOException) {
        throw DriveUploadError(step, error)
    }

    private fun request(url: String, method: String = "GET", json: JSONObject? = null): JSONObject {
        val connection = open(url, method)
        return try {
            if (json != null) connection.writeJson(json)
            connection.checkResponse("запрос Google Диска")
            connection.readJson()
        } finally { connection.disconnect() }
    }

    private fun open(address: String, method: String): HttpURLConnection {
        val url = URL(address)
        if (url.protocol != "https" || url.host != "www.googleapis.com" || url.userInfo != null ||
            url.port !in listOf(-1, 443)
        ) throw DriveUploadError("неподдерживаемый адрес сессии Google")
        return connect(url).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun HttpURLConnection.writeJson(value: JSONObject) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        val encoded = value.toString().toByteArray(Charsets.UTF_8)
        setFixedLengthStreamingMode(encoded.size)
        outputStream.use { it.write(encoded) }
    }

    private fun HttpURLConnection.checkResponse(step: String) {
        val status = responseCode
        if (status !in 200..299) throw DriveResponseError(status, step)
    }

    private fun HttpURLConnection.readJson() = JSONObject(inputStream.bufferedReader().use { it.readText() })
    private fun escapeQuery(text: String): String = text.replace("\\", "\\\\").replace("'", "\\'")
}
