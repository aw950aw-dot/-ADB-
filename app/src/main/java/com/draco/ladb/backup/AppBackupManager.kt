package com.draco.ladb.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.RemoteCommandException
import com.draco.ladb.utils.RootAccessManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
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
    private const val MAX_TAR_META_BYTES = 1L * 1024L * 1024L
    /** Exit code used by our own `if [ -d ... ]` guard: the directory simply does not exist. */
    private const val MISSING_DIRECTORY_EXIT = 44
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

    /** Outcome of reading one directory as a TAR payload (never throws). */
    private class TarAttempt(val payload: Payload?, val missing: Boolean, val error: String?)

    private data class ExtractedBackup(
        val packageName: String,
        val appLabel: String,
        val versionCode: Long,
        val signerSha256: String,
        val directory: File,
        val apks: List<File>,
        val payloads: List<Payload>
    )

    fun loadApps(context: Context, includeSystem: Boolean = false): List<AppItem> {
        val pm = context.packageManager
        // No signing flags here: loading certificates for every installed package is very slow.
        // The signature is read only for the single app that is actually backed up.
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(0)
        return installed.mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            val system = appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!includeSystem && system) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(info.packageName)
            AppItem(info.packageName, label, info.versionName ?: "—", safeVersionCode(info), appInfo, system)
        }.sortedWith(compareBy<AppItem> { it.label.lowercase() }.thenBy { it.packageName })
    }

    fun createBackup(
        context: Context,
        adb: ADB,
        packageName: String,
        output: OutputStream,
        onProgress: (String) -> Unit = {}
    ): BackupSummary {
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

        onProgress("בודק חיבור והרשאות…")
        val userId = currentUserId(adb)
        val rootAvailable = runCatching { RootAccessManager.verifyAccess(context) }.getOrDefault(false)

        val work = File(context.cacheDir, "app-backup-work-${UUID.randomUUID()}").apply { mkdirs() }
        val payloads = mutableListOf<Payload>()
        val notes = mutableListOf<String>()
        try {
            // ---- private data -------------------------------------------------------------
            onProgress("קורא נתונים פרטיים של האפליקציה…")
            val privateBase = privateDataBase(context)
            val privatePath = "$privateBase/$packageName"
            val privateTar = File(work, "private-user.tar")
            val privateAttempt = if (rootAvailable) {
                val inner = "tar -C ${quote(privateBase)} -cpf - ${quote(packageName)}"
                tryRootTar(privateTar, inner, "private_user", privatePath)
            } else {
                val inner = "tar -C ${quote(privatePath)} -cpf - ."
                tryTar(adb, privateTar, "run-as $packageName sh -c ${quote(inner)}",
                    "private_user", "RUN_AS", privatePath)
            }
            val privatePayload = privateAttempt.payload
            if (privatePayload != null) {
                payloads += privatePayload
            } else {
                val reason = privateAttempt.error?.let { " סיבה: $it" }.orEmpty()
                notes += if (rootAvailable) {
                    "נתונים פרטיים לא נכללו: Root לא הצליח לקרוא אותם.$reason"
                } else {
                    "נתונים פרטיים לא נכללו: נדרש Root או אפליקציה debuggable שתומכת ב־run-as.$reason"
                }
            }

            if (rootAvailable) {
                onProgress("קורא נתוני Device Protected…")
                val deviceBase = deviceProtectedDataBase(context)
                val devicePath = "$deviceBase/$packageName"
                val guarded = "if [ -d ${quote(devicePath)} ]; then " +
                    "tar -C ${quote(deviceBase)} -cpf - ${quote(packageName)}; else exit $MISSING_DIRECTORY_EXIT; fi"
                val deviceAttempt = tryRootTar(File(work, "private-device.tar"), guarded, "private_device", devicePath)
                val devicePayload = deviceAttempt.payload
                if (devicePayload != null) payloads += devicePayload
                else if (!deviceAttempt.missing) {
                    notes += "נתוני Device Protected לא נכללו: " + (deviceAttempt.error ?: "Root לא הצליח לקרוא אותם.")
                }
            }

            // ---- shared storage (Android/data, media, obb) --------------------------------
            val externalRoot = externalStorageBase(context)
            if (externalRoot == null) notes += "אחסון חיצוני לא נכלל: Android לא החזיר נתיב אחסון."
            val sharedPaths = externalRoot?.let { root ->
                listOf(
                    Triple("external_data", "$root/Android/data", "external_data.tar"),
                    Triple("external_media", "$root/Android/media", "external_media.tar"),
                    Triple("obb", "$root/Android/obb", "obb.tar")
                )
            }.orEmpty()
            sharedPaths.forEach { (type, base, name) ->
                onProgress("מעתיק $type…")
                val path = "$base/$packageName"
                val plain = "tar -C ${quote(base)} -cpf - ${quote(packageName)}"
                val guarded = "if [ -d ${quote(path)} ]; then $plain; else exit $MISSING_DIRECTORY_EXIT; fi"
                val viaShell = tryTar(adb, File(work, name), "sh -c ${quote(guarded)}", type, "SHELL", path)
                val attempt = if (viaShell.payload == null && !viaShell.missing && rootAvailable) {
                    tryRootTar(File(work, "root-$name"), guarded, type, path)
                } else viaShell
                val payload = attempt.payload
                when {
                    payload != null -> payloads += payload
                    attempt.missing -> Unit // the app simply has no such directory
                    else -> notes += "$type לא נכלל: " +
                        (attempt.error ?: "Android לא אפשר לקרוא את התיקייה.")
                }
            }

            // ---- manifest + ZIP, streamed straight to the destination ----------------------
            onProgress("מחשב סכומי בדיקה…")
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

            val archiveNames = listOf(MANIFEST_NAME) + apkSources.map { it.entry } + payloads.map { it.entry }
            require(archiveNames.distinct().size == archiveNames.size) {
                "לא ניתן ליצור גיבוי: נמצאו שמות קבצים כפולים בארכיון."
            }

            onProgress("כותב קובץ ZIP…")
            ZipOutputStream(BufferedOutputStream(output, 256 * 1024)).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                // APKs are already compressed: store them without spending CPU on deflate.
                zip.setLevel(Deflater.NO_COMPRESSION)
                apkSources.forEach { source ->
                    onProgress("כותב " + source.entry + "…")
                    zip.putNextEntry(ZipEntry(source.entry))
                    FileInputStream(source.file).use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
                zip.setLevel(Deflater.BEST_SPEED)
                payloads.forEach { payload ->
                    onProgress("כותב " + payload.entry + "…")
                    zip.putNextEntry(ZipEntry(payload.entry))
                    FileInputStream(payload.file).use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            return BackupSummary(
                packageName, appLabel, rootAvailable,
                payloads.any { it.type == "private_user" || it.type == "private_device" },
                payloads.map { it.type }, notes
            )
        } finally {
            work.deleteRecursively()
        }
    }

    fun restoreBackup(
        context: Context,
        adb: ADB,
        archive: InputStream,
        onProgress: (String) -> Unit = {}
    ): String {
        check(adb.running.value == true) { "יש להתחבר ל־Wireless Debugging לפני שחזור." }
        onProgress("קורא ומאמת את קובץ הגיבוי…")
        val backup = extractAndValidate(context, archive)
        try {
            val pm = context.packageManager
            var installed = runCatching { getPackageInfo(pm, backup.packageName) }.getOrNull()
            if (installed != null) {
                require(signerFingerprint(installed).equals(backup.signerSha256, ignoreCase = true)) {
                    "חתימת האפליקציה במכשיר אינה תואמת לגיבוי. לא בוצע שינוי בנתונים."
                }
                require(safeVersionCode(installed) <= backup.versionCode) {
                    "במכשיר מותקנת גרסה חדשה יותר מזו שבגיבוי. כדי למנוע פגיעה בנתונים, השחזור נעצר."
                }
            }
            if (installed == null || safeVersionCode(installed) < backup.versionCode) {
                onProgress("מתקין את האפליקציה…")
                adb.installApkSet(backup.apks)
                installed = waitForPackageInfo(pm, backup.packageName, 15_000L)
                    ?: error("ההתקנה הסתיימה, אך Android עדיין לא הציג את האפליקציה ברשימה.")
                require(signerFingerprint(installed).equals(backup.signerSha256, ignoreCase = true)) {
                    "חתימת האפליקציה שהותקנה אינה תואמת לגיבוי."
                }
                require(safeVersionCode(installed) == backup.versionCode) {
                    "גרסת האפליקציה שהותקנה אינה תואמת לגרסת הגיבוי."
                }
            }

            currentUserId(adb)
            val appUid = installed.applicationInfo?.uid
                ?: error("לא ניתן לזהות UID של האפליקציה במכשיר היעד.")
            val rootAvailable = runCatching { RootAccessManager.verifyAccess(context) }.getOrDefault(false)

            runCatching { adb.executeShell("am force-stop ${backup.packageName}", 15) }
            val restored = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val failures = mutableListOf<String>()

            backup.payloads.forEach { payload ->
                onProgress("משחזר " + payload.type + "…")
                val failure = restorePayload(context, adb, backup.packageName, appUid, rootAvailable, payload)
                if (failure == null) {
                    restored += payload.type
                } else {
                    skipped += payload.type
                    failures += payload.type + ": " + failure
                }
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
                if (failures.isNotEmpty()) append("\nסיבות:\n" + failures.joinToString("\n") + "\n")
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

    /** Restores one payload. Returns null on success, otherwise a human readable reason. */
    private fun restorePayload(
        context: Context,
        adb: ADB,
        pkg: String,
        appUid: Int,
        rootAvailable: Boolean,
        payload: Payload
    ): String? {
        return try {
            when (payload.type) {
                "private_user" -> when {
                    payload.method == "ROOT" && rootAvailable -> {
                        val base = privateDataBase(context)
                        val target = "$base/$pkg"
                        // -h: never follow a symlink that came out of the archive when changing owner.
                        val inner = "mkdir -p ${quote(base)} && tar -xpf - -C ${quote(base)} && " +
                            "chown -hR $appUid:$appUid ${quote(target)} && restorecon -RF ${quote(target)}"
                        RootAccessManager.execInFile(payload.file, inner, 600)
                        null
                    }
                    payload.method == "ROOT" -> "הגיבוי נוצר עם Root ולכן נדרש Root גם במכשיר היעד."
                    payload.method == "RUN_AS" -> {
                        val target = "${privateDataBase(context)}/$pkg"
                        val inner = "tar -xpf - -C ${quote(target)}"
                        val result = adb.execInFile(payload.file, "run-as $pkg sh -c ${quote(inner)}", 600)
                        if (result.exitCode == 0) null else result.output.ifBlank { "קוד יציאה ${result.exitCode}" }
                    }
                    else -> "שיטת שחזור לא נתמכת."
                }
                "private_device" -> {
                    if (!rootAvailable) {
                        "נדרש Root לשחזור נתוני Device Protected."
                    } else {
                        val base = deviceProtectedDataBase(context)
                        val target = "$base/$pkg"
                        val inner = "mkdir -p ${quote(base)} && tar -xpf - -C ${quote(base)} && " +
                            "chown -hR $appUid:$appUid ${quote(target)} && restorecon -RF ${quote(target)}"
                        RootAccessManager.execInFile(payload.file, inner, 600)
                        null
                    }
                }
                "external_data", "external_media", "obb" -> {
                    val externalRoot = externalStorageBase(context)
                        ?: return "Android לא החזיר נתיב אחסון חיצוני."
                    val base = when (payload.type) {
                        "external_data" -> "$externalRoot/Android/data"
                        "external_media" -> "$externalRoot/Android/media"
                        else -> "$externalRoot/Android/obb"
                    }
                    val inner = "mkdir -p ${quote(base)} && tar -xpf - -C ${quote(base)}"
                    if (payload.method == "ROOT" && rootAvailable) {
                        RootAccessManager.execInFile(payload.file, inner, 600)
                        null
                    } else {
                        val result = adb.execInFile(payload.file, "sh -c ${quote(inner)}", 600)
                        if (result.exitCode == 0) null else result.output.ifBlank { "קוד יציאה ${result.exitCode}" }
                    }
                }
                else -> "סוג נתונים לא מוכר."
            }
        } catch (error: Exception) {
            (error.message ?: error.javaClass.simpleName).take(300)
        }
    }

    private fun tryRootTar(
        file: File,
        command: String,
        type: String,
        sourcePath: String
    ): TarAttempt {
        return try {
            RootAccessManager.execOutToFile(command, file, 600)
            validateTarArchive(file, sourcePath.substringAfterLast('/'))
            TarAttempt(Payload("data/$type.tar", file, type, "ROOT", sourcePath), false, null)
        } catch (error: RemoteCommandException) {
            file.delete()
            if (error.exitCode == MISSING_DIRECTORY_EXIT) TarAttempt(null, true, null)
            else TarAttempt(null, false, error.message?.take(300))
        } catch (error: Exception) {
            file.delete()
            TarAttempt(null, false, (error.message ?: error.javaClass.simpleName).take(300))
        }
    }

    private fun tryTar(
        adb: ADB,
        file: File,
        command: String,
        type: String,
        method: String,
        sourcePath: String
    ): TarAttempt {
        return try {
            adb.execOutToFile(command, file, 600)
            validateTarArchive(file, if (method == "RUN_AS") null else sourcePath.substringAfterLast('/'))
            TarAttempt(Payload("data/$type.tar", file, type, method, sourcePath), false, null)
        } catch (error: RemoteCommandException) {
            file.delete()
            if (error.exitCode == MISSING_DIRECTORY_EXIT) TarAttempt(null, true, null)
            else TarAttempt(null, false, error.message?.take(300))
        } catch (error: Exception) {
            file.delete()
            TarAttempt(null, false, (error.message ?: error.javaClass.simpleName).take(300))
        }
    }

    private fun extractAndValidate(context: Context, archive: InputStream): ExtractedBackup {
        val root = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var manifestText: String? = null
            var manifestSeen = false
            val extracted = linkedMapOf<String, File>()
            var total = 0L
            var count = 0
            ZipInputStream(BufferedInputStream(archive, 256 * 1024)).use { zip ->
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
                val type = item.getString("type")
                require(type in setOf("private_user", "private_device", "external_data", "external_media", "obb")) {
                    "סוג נתונים לא מוכר בגיבוי."
                }
                val method = item.getString("method")
                require(method in setOf("ROOT", "RUN_AS", "SHELL")) { "שיטת גיבוי לא מוכרת." }
                validateTarArchive(file, if (method == "RUN_AS") null else pkg)
                payloads += Payload(name, file, type, method, item.optString("sourcePath"))
            }
            require(declared == extracted.keys) { "נמצאו קבצים שאינם רשומים במניפסט." }
            return ExtractedBackup(
                pkg,
                manifest.optString("appLabel", pkg),
                manifest.optLong("versionCode", 0L),
                signer,
                root,
                apkFiles,
                payloads
            )
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    private fun waitForPackageInfo(pm: PackageManager, packageName: String, timeoutMillis: Long): PackageInfo? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val info = runCatching { getPackageInfo(pm, packageName) }.getOrNull()
            if (info != null) return info
            Thread.sleep(250)
        }
        return runCatching { getPackageInfo(pm, packageName) }.getOrNull()
    }

    private fun privateDataBase(context: Context): String =
        context.dataDir.parentFile?.absolutePath
            ?: error("לא ניתן לזהות את תיקיית נתוני המשתמש הפעיל.")

    private fun deviceProtectedDataBase(context: Context): String =
        context.createDeviceProtectedStorageContext().dataDir.parentFile?.absolutePath
            ?: error("לא ניתן לזהות את תיקיית Device Protected של המשתמש הפעיל.")

    private fun externalStorageBase(context: Context): String? =
        context.getExternalFilesDir(null)?.let { filesDir ->
            filesDir.parentFile?.parentFile?.parentFile?.parentFile?.absolutePath
        }

    private fun currentUserId(adb: ADB): Int {
        val result = adb.executeShell("am get-current-user", 20)
        require(result.exitCode == 0) { "לא ניתן לזהות את המשתמש הפעיל במכשיר." }
        return result.output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull()
            ?: error("Android לא החזיר מזהה משתמש תקין; הגיבוי או השחזור נעצרו כדי למנוע גישה לנתוני משתמש אחר.")
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
        if (value.startsWith("/") || value.contains('\\')) return false
        val parts = value.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        return value.matches(Regex("(apk|data)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"))
    }

    /**
     * Validates a TAR stream before it is ever extracted (possibly with Root).
     *
     * Accepted: regular files, directories and symbolic links, plus the GNU long-name (L/K) and
     * pax (x) extension headers that real archives of app data need for long paths.
     * Rejected: hard links, devices, absolute or ".." paths, duplicate paths, and any entry that
     * would be written *through* a symbolic link that the same archive created.
     */
    private fun validateTarArchive(file: File, requiredTopLevel: String? = null) {
        FileInputStream(file).buffered().use { input ->
            val header = ByteArray(512)
            val seenPaths = HashSet<String>()
            val symlinkPaths = HashSet<String>()
            var entries = 0
            var totalBytes = 0L
            var pendingPath: String? = null
            var pendingLink: String? = null
            var pendingSize: Long? = null

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

                val type = header[156].toInt().toChar()
                val headerSize = parseTarSize(header)

                // ---- extension headers: they describe the NEXT entry -------------------------
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    require(headerSize in 0L..MAX_TAR_META_BYTES) { "כותרת TAR מורחבת גדולה מדי." }
                    val data = readFully(input, headerSize.toInt())
                    skipFully(input, tarPadding(headerSize))
                    when (type) {
                        'L' -> pendingPath = cString(data)
                        'K' -> pendingLink = cString(data)
                        'x' -> {
                            val records = parsePax(data)
                            records["path"]?.let { pendingPath = it }
                            records["linkpath"]?.let { pendingLink = it }
                            records["size"]?.let {
                                pendingSize = it.toLongOrNull()?.takeIf { value -> value >= 0L }
                                    ?: error("גודל PAX אינו תקין.")
                            }
                        }
                        else -> {
                            // Global pax headers apply to every later entry; refuse the ones that
                            // could silently change paths or sizes.
                            val records = parsePax(data)
                            require(records.keys.none { it == "path" || it == "linkpath" || it == "size" }) {
                                "כותרת PAX גלובלית לא נתמכת."
                            }
                        }
                    }
                    continue
                }

                // ---- a real entry --------------------------------------------------------------
                val isRegular = type == 0.toChar() || type == '0'
                val isDirectory = type == '5'
                val isSymlink = type == '2'
                require(isRegular || isDirectory || isSymlink) {
                    "הגיבוי כולל פריט TAR מסוג שאינו נתמך (קישור קשיח או קובץ מיוחד); השחזור נעצר מטעמי בטיחות."
                }

                val rawPath = pendingPath ?: run {
                    val name = tarString(header, 0, 100)
                    // The prefix field only exists in POSIX ustar headers; GNU headers use those
                    // bytes for timestamps.
                    val posix = String(header, 257, 6, Charsets.US_ASCII) == "ustar\u0000"
                    val prefix = if (posix) tarString(header, 345, 155) else ""
                    if (prefix.isBlank()) name else "$prefix/$name"
                }
                val linkTarget = pendingLink ?: tarString(header, 157, 100)
                val declaredSize = pendingSize ?: headerSize
                pendingPath = null
                pendingLink = null
                pendingSize = null

                val path = rawPath.removePrefix("./").let { if (isDirectory) it.trimEnd('/') else it }
                require(isSafeTarPath(path) && (path.isNotEmpty() || isDirectory)) { "נתיב לא בטוח בקובץ TAR." }
                if (path.isNotEmpty()) {
                    require(seenPaths.add(path)) { "קובץ TAR מכיל פריט כפול: $path" }
                    val parts = path.split('/')
                    var prefixPath = ""
                    for (index in 0 until parts.size - 1) {
                        prefixPath = if (prefixPath.isEmpty()) parts[index] else prefixPath + "/" + parts[index]
                        require(prefixPath !in symlinkPaths) {
                            "קובץ TAR מנסה לכתוב דרך קישור סימבולי: $path"
                        }
                    }
                    if (requiredTopLevel != null) {
                        require(parts.first() == requiredTopLevel) {
                            "קובץ TAR מכיל נתיב שאינו שייך לאפליקציה שבגיבוי."
                        }
                    }
                }
                if (isSymlink) {
                    require(path.isNotEmpty() && linkTarget.isNotEmpty() && linkTarget.none { it.code < 32 }) {
                        "קישור סימבולי לא תקין בקובץ TAR."
                    }
                    symlinkPaths.add(path)
                }

                // Only regular files carry data; directories and links are header-only.
                val dataSize = if (isRegular) declaredSize else 0L
                require(dataSize >= 0L) { "גודל פריט TAR אינו תקין." }
                totalBytes += dataSize
                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "קובץ TAR גדול מדי." }
                skipFully(input, dataSize + tarPadding(dataSize))
            }
        }
    }

    private fun tarPadding(size: Long): Long = (512L - size % 512L) % 512L

    private fun parseTarSize(header: ByteArray): Long {
        if ((header[124].toInt() and 0x80) != 0) {
            // GNU base-256 encoding, used for entries of 8 GiB and larger.
            var value = (header[124].toInt() and 0x7f).toLong()
            for (index in 125 until 136) {
                require(value <= (Long.MAX_VALUE shr 8)) { "גודל פריט TAR אינו תקין." }
                value = (value shl 8) or (header[index].toLong() and 0xffL)
            }
            return value
        }
        val text = tarString(header, 124, 12).trim()
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: error("גודל פריט TAR אינו תקין.")
    }

    private fun cString(data: ByteArray): String {
        var end = 0
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, 0, end, Charsets.UTF_8)
    }

    /** Parses pax records of the form "<length> <key>=<value>\n". */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var position = 0
        while (position < data.size) {
            var space = position
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            require(space < data.size) { "רשומת PAX פגומה." }
            val length = String(data, position, space - position, Charsets.US_ASCII).toIntOrNull()
                ?: error("רשומת PAX פגומה.")
            require(length >= space - position + 2 && position + length <= data.size) { "רשומת PAX פגומה." }
            val record = String(data, space + 1, position + length - space - 2, Charsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            position += length
        }
        return result
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            require(count >= 0) { "קובץ TAR מסתיים לפני סוף הנתונים." }
            offset += count
        }
        return data
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                require(input.read() >= 0) { "קובץ TAR מסתיים לפני סוף הנתונים." }
                remaining--
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
