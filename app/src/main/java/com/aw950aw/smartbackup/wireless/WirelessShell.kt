package com.aw950aw.smartbackup.wireless

import com.aw950aw.smartbackup.shell.CancelToken
import com.aw950aw.smartbackup.shell.ExecResult
import com.aw950aw.smartbackup.shell.PrivilegedShell
import com.aw950aw.smartbackup.shell.shellQuote
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * [PrivilegedShell] that runs commands over the app's own wireless-ADB connection (shell uid).
 * Commands use the raw "exec:" service, which is binary-safe. It has no exit code, so the code is
 * recovered with a marker or a small status file.
 *
 * Every stream is watched: pressing cancel closes it at once (a blocked read cannot be interrupted
 * any other way), and a stream that delivers nothing for a long time is closed with a clear error
 * instead of leaving the user waiting forever.
 */
class WirelessShell(private val api: WirelessApi, override val uid: Int) : PrivilegedShell {

    override val label: String
        get() = "ADB אלחוטי (shell, uid $uid)"

    private val marker = "@@EXIT@@"

    private class Guard(
        private val stream: WirelessStream,
        private val cancel: CancelToken?,
        private val idleMs: Long
    ) {
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        @Volatile var timedOut: Boolean = false
        @Volatile private var stopped: Boolean = false
        private val watcher: Thread = thread(name = "wireless-guard", isDaemon = true) {
            while (!stopped) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
                if (stopped) break
                if (cancel != null && cancel.cancelled) {
                    stream.close()
                    break
                }
                if (System.currentTimeMillis() - lastActivity > idleMs) {
                    timedOut = true
                    stream.close()
                    break
                }
            }
        }

        fun touch() {
            lastActivity = System.currentTimeMillis()
        }

        fun stop() {
            stopped = true
            watcher.interrupt()
        }
    }

    override fun exec(command: String): ExecResult {
        val stream = api.open("exec:($command) 2>&1; echo $marker\$?")
        val guard = Guard(stream, null, 120_000L)
        try {
            val text = readAll(stream.input, guard)
            if (guard.timedOut) throw IOException("המכשיר לא ענה לפקודה תוך 2 דקות.")
            return splitMarker(text)
        } finally {
            guard.stop()
            stream.close()
        }
    }

    override fun pipeOut(command: String, sink: OutputStream, errors: StringBuilder?, cancel: CancelToken?): Int {
        val status = "/data/local/tmp/sb-" + UUID.randomUUID().toString().take(12)
        val wrapped = "($command) 2>${shellQuote("$status.err")}; echo \$? > ${shellQuote(status)}"
        val stream = api.open("exec:$wrapped")
        val guard = Guard(stream, cancel, 90_000L)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancel != null && cancel.cancelled) break
                val count = stream.input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    guard.touch()
                    sink.write(buffer, 0, count)
                    guard.touch()
                }
            }
        } catch (error: IOException) {
            if (cancel != null && cancel.cancelled) {
                // closed by the guard because of cancel: reported below
            } else if (guard.timedOut) {
                throw IOException("לא התקבלו נתונים מהמכשיר במשך 90 שניות. החיבור נתקע, נסה שוב.")
            } else {
                throw error
            }
        } finally {
            guard.stop()
            stream.close()
        }
        cancel?.check()
        if (guard.timedOut) throw IOException("לא התקבלו נתונים מהמכשיר במשך 90 שניות. החיבור נתקע, נסה שוב.")
        val report = exec(
            "cat ${shellQuote(status)}; echo ---ERR---; head -c 2000 ${shellQuote("$status.err")}; " +
                "rm -f ${shellQuote(status)} ${shellQuote("$status.err")}"
        ).output
        val parts = report.split("---ERR---", limit = 2)
        val code = parts[0].trim().toIntOrNull() ?: -1
        if (errors != null && parts.size > 1) errors.append(parts[1].trim())
        return code
    }

    override fun pipeIn(command: String, source: InputStream, output: StringBuilder?, cancel: CancelToken?): Int {
        val stream = api.open("exec:($command) 2>&1; echo $marker\$?")
        val guard = Guard(stream, cancel, 180_000L)
        var collected = ""
        try {
            // Read the command's output on a second thread so a chatty command can never block our writes.
            val buffer = ByteArrayOutputStream()
            val reader = thread(name = "wireless-in-out", isDaemon = true) {
                try {
                    val chunk = ByteArray(4 * 1024)
                    while (true) {
                        val count = stream.input.read(chunk)
                        if (count < 0) break
                        if (count > 0) guard.touch()
                        synchronized(buffer) { if (buffer.size() < 64 * 1024) buffer.write(chunk, 0, count) }
                    }
                } catch (_: IOException) {
                }
            }
            try {
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    if (cancel != null && cancel.cancelled) break
                    val count = source.read(chunk)
                    if (count < 0) break
                    stream.output.write(chunk, 0, count)
                    guard.touch()
                }
                stream.output.flush()
            } catch (_: IOException) {
                // the command stopped reading (it finished, or failed); its output explains which
            }
            reader.join(10 * 60 * 1000L)
            collected = synchronized(buffer) { buffer.toString("UTF-8") }
        } finally {
            guard.stop()
            stream.close()
        }
        cancel?.check()
        if (guard.timedOut) throw IOException("המכשיר לא ענה במשך 3 דקות. החיבור נתקע, נסה שוב.")
        val result = splitMarker(collected)
        if (output != null) output.append(result.output.take(8_000))
        return result.exitCode
    }

    private fun readAll(input: InputStream, guard: Guard): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) guard.touch()
            if (out.size() < 1024 * 1024) out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }

    private fun splitMarker(text: String): ExecResult {
        val index = text.lastIndexOf(marker)
        if (index < 0) return ExecResult(-1, text.trim())
        val code = text.substring(index + marker.length).trim().toIntOrNull() ?: -1
        return ExecResult(code, text.substring(0, index).trim())
    }
}
