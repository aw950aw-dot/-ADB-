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
 */
class WirelessShell(private val api: WirelessApi, override val uid: Int) : PrivilegedShell {

    override val label: String
        get() = "ADB אלחוטי (shell, uid $uid)"

    private val marker = "@@EXIT@@"

    override fun exec(command: String): ExecResult {
        val stream = api.open("exec:($command) 2>&1; echo $marker\$?")
        try {
            val text = readAll(stream.input)
            return splitMarker(text)
        } finally {
            stream.close()
        }
    }

    override fun pipeOut(command: String, sink: OutputStream, errors: StringBuilder?, cancel: CancelToken?): Int {
        val status = "/data/local/tmp/sb-" + UUID.randomUUID().toString().take(12)
        val wrapped = "($command) 2>${shellQuote("$status.err")}; echo \$? > ${shellQuote(status)}"
        val stream = api.open("exec:$wrapped")
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancel != null && cancel.cancelled) break
                val count = stream.input.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
            }
        } finally {
            stream.close()
        }
        cancel?.check()
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
                }
                stream.output.flush()
            } catch (_: IOException) {
                // the command stopped reading (it finished, or failed); its output explains which
            }
            reader.join(10 * 60 * 1000L)
            collected = synchronized(buffer) { buffer.toString("UTF-8") }
        } finally {
            stream.close()
        }
        cancel?.check()
        val result = splitMarker(collected)
        if (output != null) output.append(result.output.take(8_000))
        return result.exitCode
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
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
