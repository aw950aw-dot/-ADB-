package com.aw950aw.smartbackup.backup

import android.content.Context
import com.aw950aw.smartbackup.shell.CancelToken
import com.aw950aw.smartbackup.shell.PrivilegedShell
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object RestoreEngine {

    /** Reads only the manifest, so the user can see what a backup contains before restoring. */
    fun readManifest(zipFile: File): BackupManifest {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw IllegalStateException("הקובץ אינו גיבוי תקין של האפליקציה הזו (חסר manifest).")
            require(entry.size in 1..(2L * 1024L * 1024L)) { "ה-manifest בגודל לא תקין." }
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            val manifest = BackupManifest.parse(text)
            require(PACKAGE_NAME_REGEX.matches(manifest.packageName)) { "שם החבילה בגיבוי אינו תקין." }
            return manifest
        }
    }

    fun run(
        context: Context,
        shell: PrivilegedShell,
        zipFile: File,
        manifest: BackupManifest,
        selection: RestoreSelection,
        cancel: CancelToken,
        progress: (String) -> Unit
    ): String {
        val pm = context.packageManager
        val pkg = manifest.packageName
        require(PACKAGE_NAME_REGEX.matches(pkg)) { "שם החבילה בגיבוי אינו תקין." }
        val report = StringBuilder()
        val workDir = File(zipFile.parentFile, "parts").apply {
            deleteRecursively()
            mkdirs()
        }

        ZipFile(zipFile).use { zip ->
            // ---- compare with what is installed ------------------------------------------
            val installed = try {
                AppScanner.packageInfo(pm, pkg)
            } catch (_: Exception) {
                null
            }
            if (installed != null) {
                val installedSigner = AppScanner.signerSha256(installed)
                require(manifest.signer.isEmpty() || installedSigner.isEmpty() || installedSigner.equals(manifest.signer, true)) {
                    "חתימת האפליקציה המותקנת שונה מזו שבגיבוי. לא בוצע שום שינוי."
                }
                require(AppScanner.versionCode(installed) <= manifest.versionCode) {
                    "במכשיר מותקנת גרסה חדשה יותר מזו שבגיבוי. כדי להגן על הנתונים, השחזור נעצר."
                }
            }
            val needsInstall = selection.installApk && manifest.apks.isNotEmpty() &&
                (installed == null || AppScanner.versionCode(installed) < manifest.versionCode)
            if (installed == null && !needsInstall) {
                throw IllegalStateException("האפליקציה אינה מותקנת, והגיבוי לא כולל APK (או שלא בחרת להתקין אותו).")
            }

            // ---- install ----------------------------------------------------------------
            if (needsInstall) {
                for (apk in manifest.apks) {
                    progress("בודק את ${apk.name}…")
                    verifyEntry(zip, apk.entry, apk.size, apk.sha256, cancel)
                }
                progress("מתקין את האפליקציה…")
                installApks(zip, shell, manifest, cancel)
                report.append("האפליקציה הותקנה (${manifest.apks.size} קבצים).\n")
            } else if (installed != null) {
                report.append("האפליקציה כבר מותקנת באותה גרסה, ההתקנה דולגה.\n")
            }

            // ---- data -------------------------------------------------------------------
            val chosen = manifest.parts.filter { it.entry in selection.partEntries }
            if (chosen.isNotEmpty()) {
                val uid = try {
                    pm.getApplicationInfo(pkg, 0).uid
                } catch (_: Exception) {
                    throw IllegalStateException("האפליקציה אינה מותקנת, לא ניתן לשחזר נתונים.")
                }
                val failures = ArrayList<String>()
                var restoredCount = 0
                for ((index, part) in chosen.withIndex()) {
                    cancel.check()
                    progress("משחזר ${partTitle(part.kind)}…")
                    val tmp = File(workDir, "part-$index.tar")
                    try {
                        extractAndVerify(zip, part, tmp, cancel)
                        TarValidator.validate(tmp, if (part.kind == "private_runas") null else pkg)
                        val command = restoreCommand(part.kind, pkg, uid, shell)
                        if (command == null) {
                            failures.add("${partTitle(part.kind)}: אין הרשאה מתאימה במכשיר הזה.")
                        } else {
                            val output = StringBuilder()
                            val exit = tmp.inputStream().use { shell.pipeIn(command, it, output, cancel) }
                            if (exit == 0) {
                                restoredCount++
                            } else {
                                failures.add("${partTitle(part.kind)}: ${output.toString().trim().take(250)}")
                            }
                        }
                    } finally {
                        tmp.delete()
                    }
                }
                report.append("נתונים ששוחזרו: $restoredCount מתוך ${chosen.size}.\n")
                for (failure in failures) report.append("• $failure\n")
            } else {
                report.append("לא נבחרו נתונים לשחזור.\n")
            }
        }
        workDir.deleteRecursively()
        return report.toString().trim()
    }

    private fun verifyEntry(zip: ZipFile, name: String, size: Long, sha256: String, cancel: CancelToken) {
        val entry = zip.getEntry(name) ?: throw IllegalStateException("חסר בקובץ הגיבוי: $name")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancel.check()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                count += read
            }
        }
        require(count == size) { "גודל הקובץ $name אינו תואם ל-manifest." }
        require(digest.digest().toHex().equals(sha256, true)) { "בדיקת השלמות נכשלה עבור $name. הגיבוי פגום." }
    }

    private fun extractAndVerify(zip: ZipFile, part: ManifestPart, target: File, cancel: CancelToken) {
        val entry = zip.getEntry(part.entry) ?: throw IllegalStateException("חסר בקובץ הגיבוי: ${part.entry}")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        zip.getInputStream(entry).use { input ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    cancel.check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    count += read
                }
            }
        }
        require(count == part.size) { "גודל ${part.entry} אינו תואם ל-manifest." }
        require(digest.digest().toHex().equals(part.sha256, true)) { "בדיקת השלמות נכשלה עבור ${part.entry}. הגיבוי פגום." }
    }

    private fun installApks(zip: ZipFile, shell: PrivilegedShell, manifest: BackupManifest, cancel: CancelToken) {
        if (manifest.apks.size == 1) {
            val apk = manifest.apks[0]
            val entry = zip.getEntry(apk.entry) ?: throw IllegalStateException("חסר בקובץ הגיבוי: ${apk.entry}")
            val output = StringBuilder()
            val exit = zip.getInputStream(entry).use { shell.pipeIn("pm install -r -S ${apk.size}", it, output, cancel) }
            require(exit == 0 && output.contains("Success")) { "ההתקנה נכשלה: ${output.toString().trim().take(250)}" }
            return
        }
        val total = manifest.apks.sumOf { it.size }
        val created = shell.exec("pm install-create -r -S $total")
        val sessionId = Regex("\\[(\\d+)]").find(created.output)?.groupValues?.get(1)
        require(created.ok && sessionId != null) { "יצירת סשן ההתקנה נכשלה: ${created.output.trim().take(250)}" }
        try {
            for (apk in manifest.apks) {
                cancel.check()
                val entry = zip.getEntry(apk.entry) ?: throw IllegalStateException("חסר בקובץ הגיבוי: ${apk.entry}")
                val output = StringBuilder()
                val exit = zip.getInputStream(entry).use {
                    shell.pipeIn("pm install-write -S ${apk.size} $sessionId ${apk.name} -", it, output, cancel)
                }
                require(exit == 0) { "כתיבת ${apk.name} נכשלה: ${output.toString().trim().take(250)}" }
            }
            val commit = shell.exec("pm install-commit $sessionId")
            require(commit.ok && commit.output.contains("Success")) { "ההתקנה נכשלה: ${commit.output.trim().take(250)}" }
        } catch (error: Throwable) {
            try {
                shell.exec("pm install-abandon $sessionId")
            } catch (_: Exception) {
            }
            throw error
        }
    }

    private fun restoreCommand(kind: String, pkg: String, uid: Int, shell: PrivilegedShell): String? {
        return when (kind) {
            "external_data" -> "mkdir -p /sdcard/Android/data && tar -xpf - -C /sdcard/Android/data"
            "external_media" -> "mkdir -p /sdcard/Android/media && tar -xpf - -C /sdcard/Android/media"
            "obb" -> "mkdir -p /sdcard/Android/obb && tar -xpf - -C /sdcard/Android/obb"
            "private_user" -> if (shell.isRoot) {
                "am force-stop $pkg; mkdir -p /data/user/0 && tar -xpf - -C /data/user/0 && " +
                    "chown -hR $uid:$uid /data/user/0/$pkg && restorecon -RF /data/user/0/$pkg"
            } else null
            "private_device" -> if (shell.isRoot) {
                "am force-stop $pkg; mkdir -p /data/user_de/0 && tar -xpf - -C /data/user_de/0 && " +
                    "chown -hR $uid:$uid /data/user_de/0/$pkg && restorecon -RF /data/user_de/0/$pkg"
            } else null
            "private_runas" -> if (shell.isRoot) {
                "am force-stop $pkg; tar -xpf - -C /data/user/0/$pkg && " +
                    "chown -hR $uid:$uid /data/user/0/$pkg && restorecon -RF /data/user/0/$pkg"
            } else {
                "am force-stop $pkg; run-as $pkg tar -xpf -"
            }
            else -> null
        }
    }
}
