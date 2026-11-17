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
import java.util.UUID
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

/** Password-encrypted export of ADB SMART preferences, files, databases and API keys. */
object AppDataBackupManager {
    private val MAGIC = "ADBSMART-BACKUP-1".toByteArray(Charsets.US_ASCII)
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val ITERATIONS = 210_000
    private const val MAX_ENTRIES = 10_000
    private const val MAX_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L

    fun createBackup(context: Context, destination: OutputStream, password: String) {
        require(password.length >= 8) { "יש לבחור סיסמה באורך 8 תווים לפחות." }
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        destination.write(MAGIC)
        destination.write(salt)
        destination.write(iv)
        val encrypted = CipherOutputStream(destination, cipher(password, salt, iv, Cipher.ENCRYPT_MODE))
        ZipOutputStream(BufferedOutputStream(encrypted)).use { zip ->
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
    }

    fun restoreBackup(context: Context, source: InputStream, password: String) {
        require(password.length >= 8) { "יש להזין את סיסמת הגיבוי." }
        val staging = File(context.cacheDir, "adb-smart-stage-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            DataInputStream(BufferedInputStream(source)).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "זה אינו קובץ גיבוי של ADB SMART." }
                val salt = ByteArray(SALT_SIZE).also { input.readFully(it) }
                val iv = ByteArray(IV_SIZE).also { input.readFully(it) }
                val decrypted = CipherInputStream(input, cipher(password, salt, iv, Cipher.DECRYPT_MODE))
                ZipInputStream(BufferedInputStream(decrypted)).use { zip ->
                    val seen = mutableSetOf<String>()
                    var count = 0
                    var totalBytes = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        count++
                        require(count <= MAX_ENTRIES) { "קובץ הגיבוי מכיל יותר מדי פריטים." }
                        val rawName = entry.name
                        require(!rawName.startsWith("/") && !rawName.contains('\\')) {
                            "קובץ הגיבוי מכיל נתיב לא בטוח."
                        }
                        val name = if (entry.isDirectory) rawName.removeSuffix("/") else rawName
                        val parts = name.split('/')
                        require(name.isNotBlank() && parts.none { it.isBlank() || it == "." || it == ".." }) {
                            "קובץ הגיבוי מכיל נתיב לא בטוח."
                        }
                        val top = parts.first()
                        require(top in setOf("shared_prefs", "databases", "files", "no_backup", "external_files", "api_keys.json", "meta.json")) {
                            "קובץ הגיבוי מכיל רכיב לא מוכר."
                        }
                        require(seen.add(name)) { "קובץ הגיבוי מכיל פריט כפול: $name" }
                        val target = File(staging, name)
                        require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "נתיב שחזור לא בטוח."
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    totalBytes += read
                                    require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                        "קובץ הגיבוי גדול מדי לפתיחה."
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            val metaFile = File(staging, "meta.json")
            require(metaFile.isFile) { "קובץ הגיבוי חסר פרטי זיהוי." }
            val meta = JSONObject(metaFile.readText())
            require(meta.optInt("format") == 1 && meta.optString("package") == context.packageName) {
                "קובץ הגיבוי אינו מתאים לאפליקציית ADB SMART הזו."
            }
            val keysFile = File(staging, "api_keys.json")
            val keys = if (keysFile.isFile) JSONObject(keysFile.readText()) else JSONObject()

            val dataRoot = File(context.applicationInfo.dataDir)
            val restorePairs = buildList {
                add(File(staging, "shared_prefs") to File(dataRoot, "shared_prefs"))
                add(File(staging, "databases") to File(dataRoot, "databases"))
                add(File(staging, "files") to context.filesDir)
                add(File(staging, "no_backup") to context.noBackupFilesDir)
                context.getExternalFilesDir(null)?.let { add(File(staging, "external_files") to it) }
            }
            restoreDirectoriesTransactionally(restorePairs)

            listOf("gemini", "openai", "anthropic", "custom").forEach { provider ->
                ApiKeyVault.save(context, keys.optString(provider, ""), provider)
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException(
                if (error.message?.contains("Tag mismatch", true) == true || error is java.io.IOException)
                    "הסיסמה שגויה או שקובץ הגיבוי פגום."
                else "לא ניתן לשחזר את הגיבוי: " + error.localizedMessage.orEmpty(),
                error
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun cipher(password: String, salt: ByteArray, iv: ByteArray, mode: Int): Cipher {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            }
        } finally {
            keyBytes.fill(0)
        }
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

    private data class RestoreDirectory(
        val destination: File,
        val staging: File,
        val previous: File
    )

    /** Prepare everything first, then swap folders atomically; roll back a partial commit. */
    private fun restoreDirectoriesTransactionally(pairs: List<Pair<File, File>>) {
        val token = UUID.randomUUID().toString()
        val prepared = mutableListOf<RestoreDirectory>()
        val committed = mutableListOf<RestoreDirectory>()
        var committedAll = false
        var rollbackFailed = false
        try {
            pairs.filter { it.first.isDirectory }.forEachIndexed { index, (source, destination) ->
                val parent = destination.parentFile ?: error("נתיב שחזור לא תקין.")
                if (!parent.exists() && !parent.mkdirs()) error("לא ניתן להכין תיקיית שחזור.")
                val stagingDir = File(parent, "." + destination.name + ".restore-" + token + "-" + index)
                val previous = File(parent, "." + destination.name + ".previous-" + token + "-" + index)
                stagingDir.deleteRecursively()
                source.copyRecursively(stagingDir, overwrite = true)
                prepared += RestoreDirectory(destination, stagingDir, previous)
            }

            prepared.forEach { item ->
                if (item.destination.exists() && !item.destination.renameTo(item.previous)) {
                    error("לא ניתן לשמור עותק זמני של " + item.destination.name + ".")
                }
                if (!item.staging.renameTo(item.destination)) {
                    if (item.previous.exists() && !item.previous.renameTo(item.destination)) rollbackFailed = true
                    error("לא ניתן להחליף את " + item.destination.name + ".")
                }
                committed += item
            }
            committedAll = true
        } catch (error: Exception) {
            committed.asReversed().forEach { item ->
                item.destination.deleteRecursively()
                if (item.previous.exists() && !item.previous.renameTo(item.destination)) rollbackFailed = true
            }
            throw error
        } finally {
            prepared.forEach {
                it.staging.deleteRecursively()
                if (committedAll || (!rollbackFailed && it.destination.exists())) it.previous.deleteRecursively()
            }
        }
    }
}
