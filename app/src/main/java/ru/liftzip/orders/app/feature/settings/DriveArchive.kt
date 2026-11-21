package ru.liftzip.orders.app.feature.settings

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ARCHIVE_ENTRY = "orders-backup.zip"
private val recoveryCodePattern = Regex("[A-Za-z0-9_-]{32}")

internal object DriveArchive {
    fun newCode(): String = Base64.encodeToString(
        ByteArray(24).also(SecureRandom()::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP,
    )

    fun encrypt(context: Context, source: Uri, output: File, code: String) {
        require(recoveryCodePattern.matches(code))
        val password = code.toCharArray()
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Локальная копия недоступна")
            input.use { raw ->
                ZipOutputStream(output.outputStream().buffered(), password).use { zip ->
                    zip.putNextEntry(ZipParameters().apply {
                        fileNameInZip = ARCHIVE_ENTRY
                        compressionMethod = CompressionMethod.DEFLATE
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    })
                    raw.copyTo(zip)
                    zip.closeEntry()
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    fun decrypt(context: Context, source: Uri, code: String): File {
        require(recoveryCodePattern.matches(code)) { "Введите 32 символа кода восстановления" }
        val output = File.createTempFile("orders-recovery-", ".zip", context.cacheDir)
        val password = code.toCharArray()
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Архив недоступен")
            input.use { raw ->
                ZipInputStream(raw.buffered(), password).use { zip ->
                    val entry = zip.nextEntry
                    require(entry != null && entry.fileName == ARCHIVE_ENTRY && entry.isEncrypted &&
                        entry.encryptionMethod == EncryptionMethod.AES) { "Неподдерживаемый формат облачной копии" }
                    output.outputStream().buffered().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= 9L * 1024 * 1024 * 1024) { "Архив слишком большой" }
                            target.write(buffer, 0, count)
                        }
                    }
                    require(zip.nextEntry == null) { "Лишние файлы в облачном архиве" }
                }
            }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            password.fill('\u0000')
        }
    }
}

/** Encrypt only the device's remembered recovery code. The archive is independent of this key. */
internal object DriveRecoveryCode {
    private const val ALIAS = "orders-drive-recovery-code"

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
    }

    fun seal(code: String): String {
        require(recoveryCodePattern.matches(code))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = cipher.doFinal(code.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(cipher.iv + value, Base64.NO_WRAP)
    }

    fun unseal(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size == 60)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.US_ASCII)
    }
}
