package com.draco.ladb.utils

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object RootAccessManager {
    private const val PREFS = "root_access"
    private const val KEY_GRANTED = "granted"

    data class Result(val granted: Boolean, val output: String)

    fun isMarkedGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GRANTED, false)

    /** Run this only after the user explicitly taps the Root authorization button. */
    fun requestAccess(context: Context, timeoutSeconds: Long = 30): Result {
        val result = runCommand("id", timeoutSeconds)
        val granted = result.first && result.second.contains("uid=0")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GRANTED, granted).apply()
        return Result(granted, result.second.ifBlank {
            if (granted) "Root פעיל" else "מנהל Root לא אישר את ההרשאה."
        })
    }

    fun verifyAccess(context: Context, timeoutSeconds: Long = 8): Boolean {
        if (!isMarkedGranted(context)) return false
        val result = runCommand("id", timeoutSeconds)
        val granted = result.first && result.second.contains("uid=0")
        if (!granted) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_GRANTED, false).apply()
        }
        return granted
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun runCommand(command: String, timeoutSeconds: Long = 30): Pair<Boolean, String> {
        require(command.isNotBlank() && command.length <= 16_000)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { output.append(it.readText()) } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2_000)
            throw IllegalStateException("בקשת Root חרגה מזמן ההמתנה.")
        }
        reader.join(5_000)
        return (process.exitValue() == 0) to output.toString().trim()
    }

    fun execOutToFile(command: String, destination: File, timeoutSeconds: Long = 600) {
        require(command.isNotBlank() && command.length <= 16_000)
        destination.parentFile?.mkdirs()
        val process = ProcessBuilder("su", "-c", command).start()
        val errorText = StringBuilder()
        val stdout = Thread {
            runCatching {
                FileOutputStream(destination).use { output ->
                    process.inputStream.use { it.copyTo(output, 64 * 1024) }
                }
            }
        }.apply { isDaemon = true; start() }
        val stderr = Thread {
            runCatching { process.errorStream.bufferedReader().use { errorText.append(it.readText()) } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdout.join(2_000)
            stderr.join(2_000)
            destination.delete()
            throw IllegalStateException("פעולת Root ליצירת גיבוי חרגה מזמן ההמתנה.")
        }
        stdout.join(5_000)
        stderr.join(5_000)
        if (stdout.isAlive || stderr.isAlive) {
            process.destroyForcibly()
            destination.delete()
            throw IllegalStateException("קריאת נתוני Root לא הסתיימה בזמן.")
        }
        val exit = process.exitValue()
        if (exit != 0) {
            destination.delete()
            throw RemoteCommandException(
                exit,
                errorText.toString().trim().ifBlank { "Root לא הצליח לקרוא את תיקיית הנתונים (קוד $exit)." }
            )
        }
        if (!destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw IllegalStateException(
                errorText.toString().trim().ifBlank { "Root לא החזיר נתונים מהתיקייה." }
            )
        }
    }

    fun execInFile(source: File, command: String, timeoutSeconds: Long = 600) {
        require(source.isFile && source.canRead())
        require(command.isNotBlank() && command.length <= 16_000)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { output.append(it.readText()) } }
        }.apply { isDaemon = true; name = "RootRestoreOutput"; start() }
        val copyFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val writer = Thread {
            try {
                source.inputStream().use { input ->
                    process.outputStream.use { target -> input.copyTo(target, 64 * 1024) }
                }
            } catch (error: Throwable) {
                copyFailure.set(error)
                runCatching { process.outputStream.close() }
            }
        }.apply { isDaemon = true; name = "RootRestoreInput"; start() }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            writer.join(2_000)
            reader.join(2_000)
            throw IllegalStateException("פעולת Root לשחזור חרגה מזמן ההמתנה.")
        }
        writer.join(5_000)
        reader.join(5_000)
        if (writer.isAlive || reader.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("העברת נתוני Root לא הסתיימה בזמן.")
        }
        copyFailure.get()?.let { throw IllegalStateException("לא ניתן להעביר את קובץ השחזור ל־Root: ${it.message}", it) }
        if (process.exitValue() != 0) {
            throw IllegalStateException(output.toString().ifBlank { "פקודת Root לשחזור נכשלה." })
        }
    }
}
