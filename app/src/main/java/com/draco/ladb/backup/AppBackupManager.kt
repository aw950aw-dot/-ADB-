package com.draco.ladb.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.RootAccessManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable app package + data archive.
 *
 * Private /data/user data is included only when the authorized ADB shell can use Root
 * or Android run-as for a debuggable package. Shared external app directories are
 * attempted through the authorized shell. A missing payload is recorded, never hidden.
 */
object AppBackupManager {
    private const val FORMAT_VERSION = 2
    private const val MANIFEST_NAME = "adb-smart-manifest.json"
    private const val MAX_ARCHIVE_ENTRIES = 160
    private const val MAX_UNCOMPRESSED_BYTES = 20L * 1024L * 1024L * 1024L
    private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

    data class AppItem(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val applicationInfo: ApplicationInfo,
        val isSystem: Boolean
    )

    data class BackupSummary(
        val packageName: String,
        val appLabel: String,
        val outputFile: File,
        val rootAvailable: Boolean,
        val privateDataIncluded: Boolean,
        val payloadNames: List<String>,
        val notes: List<String>
    )

    private data class ApkSource(
        val entry: String,
        val file: File,
        val kind: String,
        val splitName: String?
    )

    private data class Payload(
        val entry: String,
        val file: File,
        val type: String,
        val method: String,
        val sourcePath: String
    )

    private data class ExtractedBackup(
        val packageName: String,
        val appLabel: String,
        val signerSha256: String,
        val directory: File,
        val apks: List<File>,
        val payloads: List<Payload>
    )

    fun loadApps(context: Context, includeSystem: Boolean = false): List<AppItem> {
        val pm = context.packageManager
        val flags = signingFlags()
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(flags)
        return installed.mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            val system = appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!includeSystem && system) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(info.packageName)
            AppItem(info.packageName, label, info.versionName ?: "—", safeVersionCode(info), appInfo, system)
        }.sortedWith(compareBy<AppItem> { it.label.lowercase() }.thenBy { it.packageName })
    }

    fun createBackup(context: Context, adb: ADB, packageName: String, outputFile: File): BackupSummary {
        requirePackageName(packageName)
        check(adb.running.value == true) { "יש להתחבר ל־Wireless Debugging לפני גיבוי." }
        val pm = context.packageManager
        val packageInfo = getPackageInfo(pm, packageName)
        val appInfo = packageInfo.applicationInfo ?: error("לא ניתן לקבל פרטי אפליקציה")
        val appLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(packageName)

        val apkSources = mutableListOf<ApkSource>()
        val baseApk = File(appInfo.sourceDir)
        require(baseApk.isFile && baseApk.canRead()) {
            "לא ניתן לקרוא את קובץ ה־APK המקורי של האפליקציה."
        }
        apkSources += ApkSource("apk/base.apk", baseApk, "base", null)
        val splitPaths = appInfo.splitSourceDirs ?: emptyArray()
        val splitNames = packageInfo.splitNames ?: emptyArray()
        splitPaths.forEachIndexed { index, path ->
            val split = File(path)
            require(split.isFile && split.canRead()) { "לא ניתן לקרוא Split APK: ${split.name}" }
            val splitName = splitNames.getOrNull(index) ?: "split_$index"
            val safe = splitName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            apkSources += ApkSource("apk/split_${index}_$safe.apk", split, "split", splitName)
        }

        val userId = currentUserId(adb)
        val rootAvailable = runCatching { RootAccessManager.verifyAccess(context) }.getOrDefault(false)

        val work = File(context.cacheDir, "app-backup-work-${UUID.randomUUID()}").apply { mkdirs() }
        val payloads = mutableListOf<Payload>()
        val notes = mutableListOf<String>()
        try {
            val privateBase = "/data/user/$userId"
            val privatePath = "$privateBase/$packageName"
            val privateTar = File(work, "private-user.tar")
            val privatePayload = if (rootAvailable) {
                val inner = "tar -C ${quote(privateBase)} -cpf - ${quote(packageName)}"
                tryRootTar(privateTar, inner, "private_user", privatePath)
            } else {
                val inner = "tar -C ${quote(privatePath)} -cpf - ."
                tryTar(adb, privateTar, "run-as $packageName sh -c ${quote(inner)}",
                    "private_user", "RUN_AS", privatePath)
            }
            if (privatePayload != null) payloads += privatePayload
            else notes += if (rootAvailable) {
                "לא ניתן היה לקרוא את נתוני האפליקציה הפרטיים גם עם Root."
            } else {
                "נתונים פרטיים לא נכללו: נדרש Root או אפליקציה debuggable שתומכת ב־run-as."
            }

            if (rootAvailable) {
                val deviceBase = "/data/user_de/$userId"
                val devicePath = "$deviceBase/$packageName"
                val deviceTar = File(work, "private-device.tar")
                tryRootTar(
                    deviceTar,
                    "tar -C ${quote(deviceBase)} -cpf - ${quote(packageName)}",
                    "private_device", devicePath
                )?.let { payloads += it }
            }

            val sharedPaths = listOf(
                Triple("external_data", "/sdcard/Android/data", "external_data.tar"),
                Triple("external_media", "/sdcard/Android/media", "external_media.tar"),
                Triple("obb", "/sdcard/Android/obb", "obb.tar")
            )
            sharedPaths.forEach { (type, base, name) ->
                val path = "$base/$packageName"
                val tar = File(work, name)
                val plain = "tar -C ${quote(base)} -cpf - ${quote(packageName)}"
                val first = tryTar(adb, tar, "sh -c ${quote("if [ -d ${quote(path)} ]; then $plain; else exit 44; fi")}",
                    type, "SHELL", path)
                if (first != null) {
                    payloads += first
                } else if (rootAvailable) {
                    val rootTar = File(work, "root-$name")
                    tryRootTar(rootTar, "if [ -d ${quote(path)} ]; then $plain; else exit 44; fi",
                        type, path)?.let { payloads += it }
                }
            }

            val apkJson = JSONArray()
            apkSources.forEach { source ->
                apkJson.put(JSONObject()
                    .put("entry", source.entry)
                    .put("kind", source.kind)
                    .put("splitName", source.splitName ?: JSONObject.NULL)
                    .put("size", source.file.length())
                    .put("sha256", sha256(source.file)))
            }
            val payloadJson = JSONArray()
            payloads.forEach { payload ->
                payloadJson.put(JSONObject()
                    .put("entry", payload.entry)
                    .put("type", payload.type)
                    .put("method", payload.method)
                    .put("sourcePath", payload.sourcePath)
                    .put("size", payload.file.length())
                    .put("sha256", sha256(payload.file)))
            }
            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("application", "ADB Smart App Backup")
                .put("packageName", packageName)
                .put("appLabel", appLabel)
                .put("versionName", packageInfo.versionName ?: "")
                .put("versionCode", safeVersionCode(packageInfo))
                .put("signerSha256", signerFingerprint(packageInfo))
                .put("sourceUserId", userId)
                .put("createdAt", System.currentTimeMillis())
                .put("rootAvailableAtSource", rootAvailable)
                .put("privateDataIncluded", payloads.any { it.type == "private_user" || it.type == "private_device" })
                .put("apkFiles", apkJson)
                .put("payloads", payloadJson)
                .put("notes", JSONArray(notes))

            outputFile.parentFile?.mkdirs()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                apkSources.forEach { source ->
                    zip.putNextEntry(ZipEntry(source.entry))
                    FileInputStream(source.file).use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
                payloads.forEach { payload ->
                    zip.putNextEntry(ZipEntry(payload.entry))
                    FileInputStream(payload.file).use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            return BackupSummary(
                packageName, appLabel, outputFile, rootAvailable,
                payloads.any { it.type == "private_user" || it.type == "private_device" },
                payloads.map { it.type }, notes
            )
        } finally {
            work.deleteRecursively()
        }
    }

    fun restoreBackup(context: Context, adb: ADB, archive: File): String {
        check(adb.running.value == true) { "יש להתחבר ל־Wireless Debugging לפני שחזור." }
        val backup = extractAndValidate(context, archive)
        try {
            val pm = context.packageManager
            var installed = runCatching { getPackageInfo(pm, backup.packageName) }.getOrNull()
            if (installed == null) {
                val installResult = adb.installApkSet(backup.apks)
                adb.executeShell("sleep 2", 5)
                installed = getPackageInfo(pm, backup.packageName)
                if (!signerFingerprint(installed).equals(backup.signerSha256, ignoreCase = true)) {
                    error("חתימת האפליקציה שהותקנה אינה תואמת לגיבוי.")
                }
            } else {
                require(signerFingerprint(installed).equals(backup.signerSha256, ignoreCase = true)) {
                    "חתימת האפליקציה במכשיר אינה תואמת לגיבוי. לא בוצע שינוי בנתונים."
                }
            }

            val userId = currentUserId(adb)
            val appUid = installed.applicationInfo?.uid
                ?: error("לא ניתן לזהות UID של האפליקציה במכשיר היעד.")
            val rootAvailable = runCatching { RootAccessManager.verifyAccess(context) }.getOrDefault(false)

            runCatching { adb.executeShell("am force-stop ${backup.packageName}", 15) }
            val restored = mutableListOf<String>()
            val skipped = mutableListOf<String>()

            backup.payloads.forEach { payload ->
                val pkg = backup.packageName
                val success = when (payload.type) {
                    "private_user" -> {
                        if (payload.method == "ROOT" && rootAvailable) {
                            val base = "/data/user/$userId"
                            val target = "$base/$pkg"
                            val inner = "mkdir -p ${quote(base)} && tar -xpf - -C ${quote(base)} && " +
                                "chown -R $appUid:$appUid ${quote(target)} && restorecon -RF ${quote(target)}"
                            runCatching { RootAccessManager.execInFile(payload.file, inner, 600); true }.getOrDefault(false)
                        } else if (payload.method == "RUN_AS") {
                            val target = "/data/user/$userId/$pkg"
                            val inner = "tar -xpf - -C ${quote(target)}"
                            runCatching { adb.execInFile(payload.file, "run-as $pkg sh -c ${quote(inner)}", 600).exitCode == 0 }.getOrDefault(false)
                        } else false
                    }
                    "private_device" -> {
                        if (rootAvailable) {
                            val base = "/data/user_de/$userId"
                            val target = "$base/$pkg"
                            val inner = "mkdir -p ${quote(base)} && tar -xpf - -C ${quote(base)} && " +
                                "chown -R $appUid:$appUid ${quote(target)} && restorecon -RF ${quote(target)}"
                            runCatching { RootAccessManager.execInFile(payload.file, inner, 600); true }.getOrDefault(false)
                        } else false
                    }
                    "external_data", "external_media", "obb" -> {
                        val base = when (payload.type) {
                            "external_data" -> "/sdcard/Android/data"
                            "external_media" -> "/sdcard/Android/media"
                            else -> "/sdcard/Android/obb"
                        }
                        val inner = "mkdir -p ${quote(base)} && tar -xpf - -C ${quote(base)}"
                        if (payload.method == "ROOT" && rootAvailable) {
                            runCatching { RootAccessManager.execInFile(payload.file, inner, 600); true }.getOrDefault(false)
                        } else {
                            runCatching { adb.execInFile(payload.file, "sh -c ${quote(inner)}", 600).exitCode == 0 }.getOrDefault(false)
                        }
                    }
                    else -> false
                }
                if (success) restored += payload.type else skipped += payload.type
            }

            val privatePayloads = backup.payloads.filter { it.type.startsWith("private_") }
            val privateIncluded = privatePayloads.isNotEmpty()
            val privateRestored = privateIncluded && privatePayloads.all { it.type in restored }
            val result = buildString {
                append("האפליקציה: ${backup.appLabel}\n")
                append("Package: ${backup.packageName}\n")
                append("קובצי APK: ${backup.apks.size}\n")
                if (restored.isNotEmpty()) append("נתונים ששוחזרו: ${restored.distinct().joinToString(", ")}\n")
                if (skipped.isNotEmpty()) append("נתונים שלא שוחזרו: ${skipped.distinct().joinToString(", ")}\n")
                if (privateIncluded && !privateRestored) {
                    append("\nהגיבוי כולל נתונים פרטיים, אך הם לא שוחזרו. לשחזור מלא נדרש Root במכשיר היעד, או run-as לאפליקציה debuggable.")
                } else if (privateIncluded) {
                    append("\nנתונים פרטיים שוחזרו. האפליקציה נשארת עצורה עד להפעלה ידנית כדי למנוע שינוי קבצים בזמן הבדיקה.")
                } else {
                    append("\nלא נמצאו נתונים פרטיים בגיבוי. שוחזרו רק קובצי הנתונים הנגישים.")
                }
                if (skipped.isEmpty() && restored.isEmpty()) append("\nלא נמצאו קובצי נתונים לשחזור.")
            }
            return result
        } finally {
            backup.directory.deleteRecursively()
        }
    }

    private fun tryRootTar(
        file: File,
        command: String,
        type: String,
        sourcePath: String
    ): Payload? {
        return try {
            RootAccessManager.execOutToFile(command, file, 600)
            validateTarArchive(file)
            Payload("data/$type.tar", file, type, "ROOT", sourcePath)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun tryTar(
        adb: ADB,
        file: File,
        command: String,
        type: String,
        method: String,
        sourcePath: String
    ): Payload? {
        return try {
            adb.execOutToFile(command, file, 600)
            if (file.length() <= 0L) null
            else {
                validateTarArchive(file)
                Payload("data/$type.tar", file, type, method, sourcePath)
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun extractAndValidate(context: Context, archive: File): ExtractedBackup {
        val root = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var manifestText: String? = null
            var manifestSeen = false
            val extracted = linkedMapOf<String, File>()
            var total = 0L
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ARCHIVE_ENTRIES) { "קובץ הגיבוי מכיל יותר מדי פריטים." }
                    require(!entry.isDirectory && isSafeEntry(entry.name)) { "נתיב לא בטוח בתוך הגיבוי." }
                    if (entry.name == MANIFEST_NAME) {
                        require(!manifestSeen) { "המניפסט מופיע יותר מפעם אחת." }
                        manifestSeen = true
                        val bytes = zip.readBounded(256 * 1024)
                        require(bytes.size <= 256 * 1024) { "קובץ תיאור הגיבוי גדול מדי." }
                        manifestText = bytes.toString(Charsets.UTF_8)
                    } else {
                        require(!extracted.containsKey(entry.name)) {
                            "פריט כפול בגיבוי: ${entry.name}"
                        }
                        val destination = File(root, entry.name)
                        destination.parentFile?.mkdirs()
                        var written = 0L
                        FileOutputStream(destination).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                written += read
                                total += read
                                require(total <= MAX_UNCOMPRESSED_BYTES) { "הגיבוי גדול מדי לפתיחה." }
                                out.write(buffer, 0, read)
                            }
                        }
                        extracted[entry.name] = destination
                    }
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(manifestText ?: error("חסר manifest בקובץ הגיבוי."))
            require(manifest.optInt("formatVersion") == FORMAT_VERSION) { "גרסת פורמט הגיבוי אינה נתמכת." }
            val pkg = manifest.getString("packageName")
            requirePackageName(pkg)
            val signer = manifest.getString("signerSha256")
            val apkArray = manifest.getJSONArray("apkFiles")
            require(apkArray.length() in 1..64) { "רשימת קובצי APK לא תקינה." }
            val apkFiles = mutableListOf<File>()
            val declared = mutableSetOf<String>()
            var baseSeen = false
            for (i in 0 until apkArray.length()) {
                val item = apkArray.getJSONObject(i)
                val name = item.getString("entry")
                require(name.matches(Regex("apk/[A-Za-z0-9_.-]+\\.apk"))) { "שם APK לא תקין." }
                require(declared.add(name)) { "קובץ APK כפול במניפסט." }
                val file = extracted[name] ?: error("חסר קובץ APK: $name")
                verifyFile(file, item.getLong("size"), item.getString("sha256"))
                if (item.optString("kind") == "base") {
                    require(name == "apk/base.apk" && !baseSeen) { "base APK לא תקין." }
                    baseSeen = true
                } else {
                    require(item.optString("kind") == "split") { "סוג APK לא מוכר." }
                }
                apkFiles += file
            }
            require(baseSeen) { "חסר base APK." }
            val archiveInfo = getArchivePackageInfo(context.packageManager, apkFiles.first { it.name == "base.apk" })
                ?: error("Android לא הצליח לקרוא את ה־APK שבגיבוי.")
            require(archiveInfo.packageName == pkg) { "שם החבילה בתוך ה־APK אינו תואם למניפסט." }
            require(signerFingerprint(archiveInfo).equals(signer, ignoreCase = true)) {
                "חתימת ה־APK אינה תואמת למניפסט."
            }

            val payloads = mutableListOf<Payload>()
            val payloadArray = manifest.optJSONArray("payloads") ?: JSONArray()
            for (i in 0 until payloadArray.length()) {
                val item = payloadArray.getJSONObject(i)
                val name = item.getString("entry")
                require(name.matches(Regex("data/[A-Za-z0-9_.-]+\\.tar"))) { "שם קובץ נתונים לא תקין." }
                require(declared.add(name)) { "קובץ נתונים כפול במניפסט." }
                val file = extracted[name] ?: error("חסר קובץ נתונים: $name")
                verifyFile(file, item.getLong("size"), item.getString("sha256"))
                validateTarArchive(file)
                val type = item.getString("type")
                require(type in setOf("private_user", "private_device", "external_data", "external_media", "obb")) {
                    "סוג נתונים לא מוכר בגיבוי."
                }
                val method = item.getString("method")
                require(method in setOf("ROOT", "RUN_AS", "SHELL")) { "שיטת גיבוי לא מוכרת." }
                payloads += Payload(name, file, type, method, item.optString("sourcePath"))
            }
            require(declared == extracted.keys) { "נמצאו קבצים שאינם רשומים במניפסט." }
            return ExtractedBackup(pkg, manifest.optString("appLabel", pkg), signer, root, apkFiles, payloads)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    private fun currentUserId(adb: ADB): Int {
        val result = adb.executeShell("am get-current-user", 20)
        return result.output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull() ?: 0
    }

    private fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, signingFlags())
        }
    }

    private fun getArchivePackageInfo(pm: PackageManager, file: File): PackageInfo? {
        @Suppress("DEPRECATION")
        return pm.getPackageArchiveInfo(file.absolutePath, signingFlags())
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun safeVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun signerFingerprint(info: PackageInfo): String {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        require(certs.isNotEmpty()) { "לא ניתן לקרוא את חתימת האפליקציה." }
        return certs.map { sha256(it) }.sorted().joinToString(":")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun verifyFile(file: File, size: Long, hash: String) {
        require(file.isFile && file.length() == size) { "גודל קובץ לא תואם: ${file.name}" }
        require(sha256(file).equals(hash, ignoreCase = true)) { "בדיקת SHA-256 נכשלה: ${file.name}" }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun requirePackageName(value: String) {
        require(PACKAGE_PATTERN.matches(value)) { "מזהה חבילה לא תקין." }
    }

    private fun isSafeEntry(value: String): Boolean {
        if (value == MANIFEST_NAME) return true
        if (value.startsWith("/") || value.contains("..") || value.contains('\\')) return false
        return value.matches(Regex("(apk|data)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"))
    }

    /**
     * Validate TAR paths and reject links/extensions before extraction with Root.
     * Only ordinary USTAR files and directories are accepted.
     */
    private fun validateTarArchive(file: File) {
        FileInputStream(file).buffered().use { input ->
            val header = ByteArray(512)
            var entries = 0
            var totalBytes = 0L
            while (true) {
                var position = 0
                while (position < 512) {
                    val count = input.read(header, position, 512 - position)
                    if (count < 0) {
                        require(position == 0) { "קובץ TAR פגום: כותרת חלקית." }
                        return
                    }
                    position += count
                }
                if (header.all { it == 0.toByte() }) return
                entries++
                require(entries <= 200_000) { "קובץ TAR מכיל יותר מדי פריטים." }
                val rawName = tarString(header, 0, 100)
                val rawPrefix = tarString(header, 345, 155)
                val rawPath = (if (rawPrefix.isBlank()) rawName else "$rawPrefix/$rawName")
                val type = header[156].toInt().toChar()
                val path = rawPath.removePrefix("./").let { if (type == '5') it.trimEnd('/') else it }
                require(type == 0.toChar() || type == '0' || type == '5') {
                    "הגיבוי כולל קישור או סוג קובץ TAR שאינו נתמך; השחזור נעצר מטעמי בטיחות."
                }
                require(isSafeTarPath(path) && (path.isNotEmpty() || type == '5')) { "נתיב לא בטוח בקובץ TAR." }
                val sizeText = tarString(header, 124, 12).trim()
                val size = if (sizeText.isBlank()) 0L else sizeText.trimStart('0').ifBlank { "0" }.toLongOrNull(8)
                    ?: error("גודל פריט TAR אינו תקין.")
                require(size >= 0L) { "גודל פריט TAR אינו תקין." }
                totalBytes += size
                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "קובץ TAR גדול מדי." }
                var remaining = ((size + 511L) / 512L) * 512L
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped > 0) remaining -= skipped
                    else {
                        val one = input.read()
                        require(one >= 0) { "קובץ TAR מסתיים לפני סוף הנתונים." }
                        remaining--
                    }
                }
            }
        }
    }

    private fun tarString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun isSafeTarPath(path: String): Boolean {
        if (path.isEmpty() || path == "." || path == "./") return true
        if (path.startsWith("/") || path.startsWith("~") || path.contains('\\')) return false
        val parts = path.split('/')
        return parts.isNotEmpty() && parts.none { it == ".." || it == "" } &&
            parts.none { it.any { ch -> ch.code < 32 } }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(out.size() + read <= maxBytes) { "קובץ המניפסט גדול מדי." }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
