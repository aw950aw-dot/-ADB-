package com.draco.ladb.backup

import android.content.Context
import com.draco.ladb.BuildConfig
import com.draco.ladb.utils.ApiKeyVault
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-encrypted backup of ADB SMART private settings, files, databases and API keys. */
object AppDataBackupManager {
    private val MAGIC = "ADBSMART-BACKUP-1".toByteArray(Charsets.US_ASCII)
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val ITERATIONS = 210_000

    fun createBackup(context: Context, destination: OutputStream, password: String) {
        require(password.length >= 8) { "יש לבחור סיסמה באורך 8 תווים לפחות." }
        val zipFile = File(context.cacheDir, "adb-smart-data-${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                val dataRoot = File(context.applicationInfo.dataDir)
                addDirectory(zip, "shared_prefs", File(dataRoot, "shared_prefs"), skipApiKeyVault = true)
                addDirectory(zip, "databases", File(dataRoot, "databases"))
                addDirectory(zip, "files", context.filesDir)
                addDirectory(zip, "no_backup", context.noBackupFilesDir)
                context.getExternalFilesDir(null)?.let { addDirectory(zip, "external_files", it) }

                val keys = JSONObject()
                listOf("gemini", "openai", "anthropic", "custom").forEach { provider ->
                    val value = ApiKeyVault.read(context, provider)
                    if (value.isNotBlank()) keys.put(provider, value)
                }
                addBytes(zip, "api_keys.json", keys.toString().toByteArray(Charsets.UTF_8))

                val meta = JSONObject()
                    .put("format", 1)
                    .put("package", context.packageName)
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("created_at", System.currentTimeMillis())
                addBytes(zip, "meta.json", meta.toString().toByteArray(Charsets.UTF_8))
            }

            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
            val encryptor = cipher(password, salt, iv, Cipher.ENCRYPT_MODE)
            destination.write(MAGIC)
            destination.write(salt)
            destination.write(iv)
            CipherOutputStream(destination, encryptor).use { encrypted ->
                FileInputStream(zipFile).use { it.copyTo(encrypted, 64 * 1024) }
            }
        } finally {
            zipFile.delete()
        }
    }

    fun restoreBackup(context: Context, source: InputStream, password: String) {
        require(password.length >= 8) { "יש להזין את סיסמת הגיבוי." }
        val stamp = System.currentTimeMillis()
        val encryptedFile = File(context.cacheDir, "adb-smart-import-$stamp.bin")
        val zipFile = File(context.cacheDir, "adb-smart-import-$stamp.zip")
        val staging = File(context.cacheDir, "adb-smart-stage-$stamp")
        try {
            FileOutputStream(encryptedFile).use { source.copyTo(it, 64 * 1024) }
            FileInputStream(encryptedFile).use { fileInput ->
                val input = DataInputStream(BufferedInputStream(fileInput))
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "זה אינו קובץ גיבוי של ADB SMART." }
                val salt = ByteArray(SALT_SIZE).also { input.readFully(it) }
                val iv = ByteArray(IV_SIZE).also { input.readFully(it) }
                val decryptor = cipher(password, salt, iv, Cipher.DECRYPT_MODE)
                try {
                    CipherInputStream(input, decryptor).use { decrypted ->
                        FileOutputStream(zipFile).use { decrypted.copyTo(it, 64 * 1024) }
                    }
                } catch (_: Exception) {
                    throw IllegalArgumentException("הסיסמה שגויה או שקובץ הגיבוי פגום.")
                }
            }

            staging.mkdirs()
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith("/") && name.split('/').none { it == ".." || it.isBlank() }) {
                        "קובץ הגיבוי מכיל נתיב לא בטוח."
                    }
                    val top = name.substringBefore('/')
                    require(top in setOf("shared_prefs", "databases", "files", "no_backup", "external_files", "api_keys.json", "meta.json")) {
                        "קובץ הגיבוי מכיל רכיב לא מוכר."
                    }
                    val target = File(staging, name)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        "נתיב שחזור לא בטוח."
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output, 64 * 1024) }
                    }
                    zip.closeEntry()
                }
            }

            val metaFile = File(staging, "meta.json")
            require(metaFile.isFile) { "קובץ הגיבוי חסר פרטי זיהוי." }
            val meta = JSONObject(metaFile.readText())
            require(meta.optInt("format") == 1 && meta.optString("package") == context.packageName) {
                "קובץ הגיבוי אינו מתאים לגרסת או לחבילת ADB SMART הזו."
            }

            val dataRoot = File(context.applicationInfo.dataDir)
            restoreDirectory(File(staging, "shared_prefs"), File(dataRoot, "shared_prefs"))
            restoreDirectory(File(staging, "databases"), File(dataRoot, "databases"))
            restoreDirectory(File(staging, "files"), context.filesDir)
            restoreDirectory(File(staging, "no_backup"), context.noBackupFilesDir)
            context.getExternalFilesDir(null)?.let { restoreDirectory(File(staging, "external_files"), it) }

            // Android Keystore keys are install-bound; API keys are re-encrypted with fresh local keys.
            val keysFile = File(staging, "api_keys.json")
            val keys = if (keysFile.isFile) JSONObject(keysFile.readText()) else JSONObject()
            listOf("gemini", "openai", "anthropic", "custom").forEach { provider ->
                ApiKeyVault.save(context, keys.optString(provider, ""), provider)
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("לא ניתן לקרוא את הגיבוי. בדוק את הסיסמה ונסה שוב.")
        } finally {
            encryptedFile.delete()
            zipFile.delete()
            staging.deleteRecursively()
        }
    }

    private fun cipher(password: String, salt: ByteArray, iv: ByteArray, mode: Int): Cipher {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        keyBytes.fill(0)
        return cipher
    }

    private fun addDirectory(
        zip: ZipOutputStream,
        archiveName: String,
        directory: File,
        skipApiKeyVault: Boolean = false
    ) {
        if (!directory.exists() || !directory.isDirectory) return
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            if (skipApiKeyVault && relative == "ai_secure.xml") return@forEach
            zip.putNextEntry(ZipEntry("$archiveName/$relative"))
            FileInputStream(file).use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
        }
    }

    private fun addBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun restoreDirectory(source: File, destination: File) {
        if (!source.exists()) return
        if (destination.exists()) destination.deleteRecursively()
        source.copyRecursively(destination, overwrite = true)
    }
}
