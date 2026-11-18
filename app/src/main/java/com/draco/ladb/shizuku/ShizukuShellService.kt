package com.draco.ladb.shizuku

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs commands in the user-approved Shizuku process (normally shell UID 2000).
 * This class is instantiated by Shizuku and is not an exported Android service.
 */
class ShizukuShellService : IUserShellService.Stub() {
    override fun execute(command: String, timeoutMillis: Long): String {
        require(command.isNotBlank() && command.length <= 12_000) {
            "פקודה ריקה או ארוכה מדי"
        }
        val timeout = timeoutMillis.coerceIn(1_000L, 120_000L)
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = ByteArrayOutputStream()
        val reader = thread(name = "ShizukuShellOutput", isDaemon = true) {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = (1024 * 1024 - output.size()).coerceAtLeast(0)
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }.onFailure { Log.w("ADBShizuku", "Cannot read shell output", it) }
        }

        val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            reader.join(1_000)
            val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
            return "124\n" + text + "\nהפקודה חרגה מזמן ההמתנה."
        }

        reader.join(2_000)
        if (reader.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("קריאת פלט Shizuku לא הסתיימה בזמן")
        }
        val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
        return process.exitValue().toString() + "\n" + text
    }

    override fun destroy() {
        System.exit(0)
    }
}
