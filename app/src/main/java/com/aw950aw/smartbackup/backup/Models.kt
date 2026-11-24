package com.aw950aw.smartbackup.backup

import com.aw950aw.smartbackup.shell.CancelToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

const val FORMAT_VERSION = 3
const val MANIFEST_ENTRY = "manifest.json"

data class AppEntry(val packageName: String, val label: String, val isSystem: Boolean)

data class AppDetail(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val debuggable: Boolean,
    val splitCount: Int,
    val apkBytes: Long
)

data class BackupOptions(
    val apk: Boolean = true,
    val externalData: Boolean = true,
    val media: Boolean = true,
    val obb: Boolean = true,
    val privateData: Boolean = true
)

data class BackupReport(val label: String, val included: List<String>, val notes: List<String>, val totalBytes: Long)

data class RestoreSelection(val installApk: Boolean, val partEntries: Set<String>)

data class ManifestApk(val entry: String, val name: String, val size: Long, val sha256: String)

data class ManifestPart(val entry: String, val kind: String, val size: Long, val sha256: String, val warning: String)

data class BackupManifest(
    val created: Long,
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val signer: String,
    val sourceLabel: String,
    val apks: List<ManifestApk>,
    val parts: List<ManifestPart>,
    val notes: List<String>
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("created", created)
        root.put(
            "app",
            JSONObject()
                .put("package", packageName)
                .put("label", label)
                .put("versionName", versionName)
                .put("versionCode", versionCode)
                .put("signer", signer)
        )
        root.put("source", sourceLabel)
        val apkArray = JSONArray()
        for (apk in apks) {
            apkArray.put(
                JSONObject()
                    .put("entry", apk.entry)
                    .put("name", apk.name)
                    .put("size", apk.size)
                    .put("sha256", apk.sha256)
            )
        }
        root.put("apks", apkArray)
        val partArray = JSONArray()
        for (part in parts) {
            partArray.put(
                JSONObject()
                    .put("entry", part.entry)
                    .put("kind", part.kind)
                    .put("size", part.size)
                    .put("sha256", part.sha256)
                    .put("warning", part.warning)
            )
        }
        root.put("parts", partArray)
        val noteArray = JSONArray()
        for (note in notes) noteArray.put(note)
        root.put("notes", noteArray)
        return root.toString(2)
    }

    companion object {
        fun parse(text: String): BackupManifest {
            val root = JSONObject(text)
            val format = root.getInt("format")
            require(format == FORMAT_VERSION) { "גרסת פורמט הגיבוי ($format) אינה נתמכת באפליקציה הזו." }
            val app = root.getJSONObject("app")
            val apks = ArrayList<ManifestApk>()
            val apkArray = root.getJSONArray("apks")
            for (index in 0 until apkArray.length()) {
                val item = apkArray.getJSONObject(index)
                apks.add(
                    ManifestApk(
                        item.getString("entry"),
                        item.getString("name"),
                        item.getLong("size"),
                        item.getString("sha256")
                    )
                )
            }
            val parts = ArrayList<ManifestPart>()
            val partArray = root.getJSONArray("parts")
            for (index in 0 until partArray.length()) {
                val item = partArray.getJSONObject(index)
                parts.add(
                    ManifestPart(
                        item.getString("entry"),
                        item.getString("kind"),
                        item.getLong("size"),
                        item.getString("sha256"),
                        item.optString("warning", "")
                    )
                )
            }
            val notes = ArrayList<String>()
            val noteArray = root.optJSONArray("notes")
            if (noteArray != null) {
                for (index in 0 until noteArray.length()) notes.add(noteArray.getString(index))
            }
            return BackupManifest(
                created = root.getLong("created"),
                packageName = app.getString("package"),
                label = app.getString("label"),
                versionName = app.optString("versionName", ""),
                versionCode = app.getLong("versionCode"),
                signer = app.optString("signer", ""),
                sourceLabel = root.optString("source", ""),
                apks = apks,
                parts = parts,
                notes = notes
            )
        }
    }
}

fun partTitle(kind: String): String = when (kind) {
    "external_data" -> "נתוני Android/data"
    "external_media" -> "מדיה (Android/media)"
    "obb" -> "קובצי OBB"
    "private_user" -> "נתונים פרטיים (Root)"
    "private_device" -> "נתונים פרטיים מוגני-מכשיר (Root)"
    "private_runas" -> "נתונים פרטיים (אפליקציית debug)"
    else -> kind
}

val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9_.]+$")

fun ByteArray.toHex(): String {
    val builder = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        builder.append("0123456789abcdef"[value shr 4])
        builder.append("0123456789abcdef"[value and 0x0f])
    }
    return builder.toString()
}

fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}

/** Counts and hashes everything that passes through. Does not close the target. */
class DigestCountingStream(private val target: OutputStream) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        target.write(b)
        digest.update(b.toByte())
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        target.write(b, off, len)
        digest.update(b, off, len)
        count += len
    }

    override fun flush() {
        target.flush()
    }

    fun sha256(): String = digest.digest().toHex()
}

fun copyWithCancel(input: InputStream, output: OutputStream, cancel: CancelToken) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        cancel.check()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
}
