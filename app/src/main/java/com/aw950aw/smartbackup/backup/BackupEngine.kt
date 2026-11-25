package com.aw950aw.smartbackup.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.aw950aw.smartbackup.shell.CancelToken
import com.aw950aw.smartbackup.shell.PrivilegedShell
import com.aw950aw.smartbackup.shell.shellQuote
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one ZIP: the APK files, one TAR per kind of data, and a manifest with sizes and SHA-256
 * hashes. Data is streamed straight from the privileged shell into the ZIP, so no temporary
 * copies are made and the free space needed equals the size of the backup itself.
 */
object BackupEngine {

    private class PartSpec(val kind: String, val title: String, val existsPath: String?, val command: String)

    fun run(
        context: Context,
        shell: PrivilegedShell,
        packageName: String,
        options: BackupOptions,
        output: OutputStream,
        cancel: CancelToken,
        progress: (String) -> Unit
    ): BackupReport {
        require(PACKAGE_NAME_REGEX.matches(packageName)) { "שם חבילה לא תקין." }
        val pm = context.packageManager
        val info = AppScanner.packageInfo(pm, packageName)
        val appInfo = info.applicationInfo ?: throw IllegalStateException("לא ניתן לקרוא את פרטי האפליקציה.")
        val label = try {
            appInfo.loadLabel(pm).toString()
        } catch (_: Exception) {
            packageName
        }
        val debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val apkApks = ArrayList<ManifestApk>()
        val parts = ArrayList<ManifestPart>()
        val notes = ArrayList<String>()
        val included = ArrayList<String>()
        var totalBytes = 0L

        val zip = ZipOutputStream(BufferedOutputStream(output, 256 * 1024))
        try {
            // ---- APK files ------------------------------------------------------------------
            if (options.apk) {
                val sources = ArrayList<Pair<String, File>>()
                sources.add("base.apk" to File(appInfo.sourceDir))
                val splits = appInfo.splitSourceDirs ?: emptyArray()
                for (path in splits) {
                    val file = File(path)
                    val safe = file.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    sources.add(safe to file)
                }
                for ((name, file) in sources) {
                    cancel.check()
                    progress("מעתיק $name…")
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    val entryName = "apk/$name"
                    zip.putNextEntry(ZipEntry(entryName))
                    val sink = DigestCountingStream(zip)
                    copyApk(shell, file, ProgressOutputStream(sink, "מעתיק $name", file.length(), progress), cancel)
                    zip.closeEntry()
                    apkApks.add(ManifestApk(entryName, name, sink.count, sink.sha256()))
                    totalBytes += sink.count
                }
                included.add("קובצי APK (${sources.size})")
            }

            // ---- data ----------------------------------------------------------------------
            for (spec in planParts(shell, packageName, options, debuggable, notes)) {
                cancel.check()
                if (spec.existsPath != null && !directoryExists(shell, spec.existsPath)) continue
                progress("מעתיק ${spec.title}…")
                val entryName = "data/${spec.kind}.tar"
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(entryName))
                val sink = DigestCountingStream(zip)
                val errors = StringBuilder()
                val shown = ProgressOutputStream(sink, "מעתיק ${spec.title}", -1L, progress)
                val exit = shell.pipeOut(spec.command, shown, errors, cancel)
                zip.closeEntry()
                if (sink.count == 0L) {
                    notes.add("${spec.title}: לא התקבלו נתונים. ${errors.toString().trim().take(200)}")
                    continue
                }
                val warning = if (exit != 0) errors.toString().trim().take(300).ifBlank { "הפקודה הסתיימה בקוד $exit" } else ""
                if (warning.isNotEmpty()) notes.add("${spec.title}: הועתק עם אזהרות. $warning")
                parts.add(ManifestPart(entryName, spec.kind, sink.count, sink.sha256(), warning))
                totalBytes += sink.count
                included.add(spec.title)
            }

            if (options.apk.not() && parts.isEmpty()) {
                throw IllegalStateException("לא נבחר דבר לגיבוי, או שלא נמצאו נתונים שניתן לגבות.")
            }

            // ---- manifest -------------------------------------------------------------------
            val manifest = BackupManifest(
                created = System.currentTimeMillis(),
                packageName = packageName,
                label = label,
                versionName = info.versionName ?: "",
                versionCode = AppScanner.versionCode(info),
                signer = AppScanner.signerSha256(info),
                sourceLabel = shell.label,
                apks = apkApks,
                parts = parts,
                notes = notes
            )
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            progress("מסיים…")
        } finally {
            try {
                zip.close()
            } catch (_: IOException) {
            }
        }
        return BackupReport(label, included, notes, totalBytes)
    }

    private fun copyApk(shell: PrivilegedShell, file: File, sink: OutputStream, cancel: CancelToken) {
        // Reading the APK directly is the fastest way and needs no shell at all.
        var direct: FileInputStream? = null
        try {
            direct = FileInputStream(file)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        if (direct != null) {
            direct.use { copyWithCancel(it, sink, cancel) }
            return
        }
        val errors = StringBuilder()
        val exit = shell.pipeOut("cat ${shellQuote(file.absolutePath)}", sink, errors, cancel)
        if (exit != 0) {
            throw IllegalStateException("קריאת ${file.name} נכשלה: ${errors.toString().trim().take(200)}")
        }
    }

    private fun directoryExists(shell: PrivilegedShell, path: String): Boolean {
        val result = shell.exec("[ -d ${shellQuote(path)} ] && echo yes || echo no")
        return result.output.trim().endsWith("yes")
    }

    private fun planParts(
        shell: PrivilegedShell,
        pkg: String,
        options: BackupOptions,
        debuggable: Boolean,
        notes: MutableList<String>
    ): List<PartSpec> {
        val q = shellQuote(pkg)
        val result = ArrayList<PartSpec>()
        if (options.externalData) {
            result.add(
                PartSpec(
                    "external_data", partTitle("external_data"), "/sdcard/Android/data/$pkg",
                    "tar -C /sdcard/Android/data -cf - $q"
                )
            )
        }
        if (options.media) {
            result.add(
                PartSpec(
                    "external_media", partTitle("external_media"), "/sdcard/Android/media/$pkg",
                    "tar -C /sdcard/Android/media -cf - $q"
                )
            )
        }
        if (options.obb) {
            result.add(
                PartSpec(
                    "obb", partTitle("obb"), "/sdcard/Android/obb/$pkg",
                    "tar -C /sdcard/Android/obb -cf - $q"
                )
            )
        }
        if (options.privateData) {
            if (shell.isRoot) {
                result.add(
                    PartSpec(
                        "private_user", partTitle("private_user"), "/data/user/0/$pkg",
                        "tar -C /data/user/0 -cf - $q"
                    )
                )
                result.add(
                    PartSpec(
                        "private_device", partTitle("private_device"), "/data/user_de/0/$pkg",
                        "tar -C /data/user_de/0 -cf - $q"
                    )
                )
            } else if (debuggable) {
                result.add(
                    PartSpec(
                        "private_runas", partTitle("private_runas"), null,
                        "run-as $q tar -cf - ."
                    )
                )
            } else {
                notes.add("נתונים פרטיים לא נכללו: נדרשת גישת Root, או אפליקציה במצב debug.")
            }
        }
        return result
    }
}
