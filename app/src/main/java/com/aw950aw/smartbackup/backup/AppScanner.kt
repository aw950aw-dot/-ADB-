package com.aw950aw.smartbackup.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

object AppScanner {

    /** All installed apps except this one, sorted by name. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val infos = pm.getInstalledApplications(0)
        val result = ArrayList<AppEntry>()
        for (info in infos) {
            if (info.packageName == context.packageName) continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val label = try {
                info.loadLabel(pm).toString()
            } catch (_: Exception) {
                info.packageName
            }
            result.add(AppEntry(info.packageName, label, isSystem))
        }
        result.sortBy { it.label.lowercase() }
        return result
    }

    /** Package info including the signing certificate (needed to match backups to apps). */
    @Suppress("DEPRECATION")
    fun packageInfo(pm: PackageManager, packageName: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageInfo(packageName, flags)
    }

    fun versionCode(info: PackageInfo): Long = PackageInfoCompat.getLongVersionCode(info)

    @Suppress("DEPRECATION")
    fun signerSha256(info: PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val first = signatures?.firstOrNull() ?: return ""
        return sha256Hex(first.toByteArray())
    }

    fun detail(context: Context, packageName: String): AppDetail {
        val pm = context.packageManager
        val info = packageInfo(pm, packageName)
        val appInfo = info.applicationInfo ?: throw IllegalStateException("לא ניתן לקרוא את פרטי האפליקציה")
        val label = try {
            appInfo.loadLabel(pm).toString()
        } catch (_: Exception) {
            packageName
        }
        val splits = appInfo.splitSourceDirs ?: emptyArray()
        var bytes = File(appInfo.sourceDir).length()
        for (path in splits) bytes += File(path).length()
        return AppDetail(
            packageName = packageName,
            label = label,
            versionName = info.versionName ?: "",
            versionCode = versionCode(info),
            debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            splitCount = splits.size,
            apkBytes = bytes
        )
    }
}
