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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.liftzip.orders.app.OrdersApplication
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val DRIVE_FOLDER = "application/vnd.google-apps.folder"
private const val API = "https://www.googleapis.com/drive/v3"
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
        WorkManager.getInstance(context).enqueueUniqueWork(
            DRIVE_JOB, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(DRIVE_JOB) }
}

class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as OrdersApplication
        val store = DriveBackupStore(app)
        val state = store.settings.first()
        val email = state.email ?: return@withContext Result.success()
        val sealed = state.sealedCode ?: return@withContext Result.success()
        val uri = app.settings.localBackup.first().folder ?: return@withContext Result.success()
        val archive = latestDailyBackup(DocumentFile.fromTreeUri(app, uri))
            ?: return@withContext Result.success()
        val name = archive.name ?: return@withContext Result.success()
        if (state.lastFile == name) return@withContext Result.success()

        var stage = "доступ к Google Диску"
        try {
            val result = Tasks.await(Identity.getAuthorizationClient(app).authorize(driveAuthorization(email)), 30, TimeUnit.SECONDS)
            val token = result.accessToken
            if (result.hasResolution() || token.isNullOrBlank()) {
                store.record(sealed, error = "Откройте настройки и обновите разрешение Google Диска.")
                return@withContext Result.success()
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
                    ensureActive()
                    if (store.settings.first().sealedCode != sealed) return@withContext Result.success()
                    stage = "отправка архива"
                    val coroutine = currentCoroutineContext()
                    drive.upload(folder, fileName, encrypted) { coroutine.ensureActive() }
                } finally {
                    encrypted.delete()
                }
            }
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
        val fields = "id,size,sha256Checksum,md5Checksum"
        val metadata = JSONObject().put("name", name).put("mimeType", "application/zip")
            .put("parents", org.json.JSONArray(listOf(folder)))
        val boundary = "orders-" + java.util.UUID.randomUUID()
        val prefix = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadata + "\r\n--$boundary\r\nContent-Type: application/zip\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        val upload = open("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=$fields", "POST")
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val saved = try {
            upload.doOutput = true
            upload.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            upload.setFixedLengthStreamingMode(length + prefix.size + suffix.size)
            transferStep("передача архива") {
                upload.outputStream.use { output ->
                    output.write(prefix)
                    file.inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            sha.update(buffer, 0, count)
                            md5.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    output.write(suffix)
                }
            }
            transferStep("ответ на загрузку") {
                upload.checkResponse("приём архива Google Диском")
                upload.readJson()
            }
        } finally { upload.disconnect() }

        val id = URLEncoder.encode(saved.getString("id"), "UTF-8")
        val checksumFields = listOf("sha256Checksum", "md5Checksum")
        val info = if (saved.has("size") && checksumFields.any { saved.optString(it).isNotBlank() }) saved
            else request("$API/files/$id?fields=$fields")
        val remoteSha = info.optString("sha256Checksum")
        val matches = if (remoteSha.isNotBlank()) remoteSha.equals(sha.hexDigest(), ignoreCase = true)
            else info.optString("md5Checksum").equals(md5.hexDigest(), ignoreCase = true)
        if (info.optLong("size", -1) != length || !matches) {
            runCatching { request("$API/files/$id", "PATCH", JSONObject().put("trashed", true)) }
            throw DriveUploadError("контрольная сумма копии не совпала")
        }
    }

    private inline fun <T> transferStep(step: String, block: () -> T): T = try {
        block()
    } catch (error: DriveResponseError) {
        throw error
    } catch (error: IOException) {
        throw DriveUploadError(step, error)
    }

    private fun java.security.MessageDigest.hexDigest(): String = digest().joinToString("") { "%02x".format(it) }

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
