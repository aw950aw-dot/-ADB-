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
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private object EndOfStream

/** Closing a library stream can itself block, so it is always done on a throwaway thread. */
private fun closeAsync(stream: WirelessStream) {
    thread(name = "wireless-close", isDaemon = true) {
        try {
            stream.close()
        } catch (_: Throwable) {
        }
    }
}

/**
 * [PrivilegedShell] that runs commands over the app's own wireless-ADB connection (shell uid).
 * Commands use the raw "exec:" service, which is binary-safe. It has no exit code, so the code is
 * recovered with a marker or a small status file.
 *
 * The library's calls (open, read, write, close) can block forever when the connection is dead and
 * cannot be interrupted. The calling thread therefore NEVER blocks on them: opening runs on a worker
 * with a deadline, and reading runs on a pump thread that hands data over through a queue. The
 * caller only waits on that queue, so cancel and the idle timeout always work.
 */
class WirelessShell(private val api: WirelessApi, override val uid: Int) : PrivilegedShell {

    override val label: String
        get() = "ADB אלחוטי (shell, uid $uid)"

    private val marker = "@@EXIT@@"

    private class Pump(private val stream: WirelessStream) {
        val queue = LinkedBlockingQueue<Any>(16)

        @Volatile
        private var stopped = false

        private val reader: Thread = thread(name = "wireless-read", isDaemon = true) {
            try {
                val buffer = ByteArray(64 * 1024)
                while (!stopped) {
                    val count = stream.input.read(buffer)
                    if (count < 0) break
                    if (count > 0) queue.put(buffer.copyOf(count))
                }
                if (!stopped) queue.put(EndOfStream)
            } catch (error: Throwable) {
                if (!stopped) {
                    try {
                        queue.put(error)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }

        fun abandon() {
            stopped = true
            reader.interrupt()
            closeAsync(stream)
        }
    }

    /** Opens a stream, but never waits longer than [timeoutMs] and gives up at once when cancelled. */
    private fun openBounded(destination: String, cancel: CancelToken?, timeoutMs: Long = 20_000L): WirelessStream {
        val holder = arrayOfNulls<WirelessStream>(1)
        val failure = arrayOfNulls<Throwable>(1)
        val abandoned = AtomicBoolean(false)
        val worker = thread(name = "wireless-open", isDaemon = true) {
            try {
                val opened = api.open(destination)
                if (abandoned.get()) closeAsync(opened) else holder[0] = opened
            } catch (error: Throwable) {
                failure[0] = error
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (worker.isAlive) {
            if (cancel != null && cancel.cancelled) {
                abandoned.set(true)
                throw CancellationException("הפעולה בוטלה")
            }
            if (System.currentTimeMillis() > deadline) {
                abandoned.set(true)
                WirelessLink.linkDied("המכשיר לא הגיב לפתיחת פעולה חדשה. החיבור כנראה נותק.")
                throw IOException("החיבור למכשיר לא מגיב. נתק, התחבר מחדש ונסה שוב.")
            }
            worker.join(150)
        }
        val error = failure[0]
        if (error != null) throw IOException("פתיחת הפעולה במכשיר נכשלה: ${error.message ?: error.javaClass.simpleName}")
        return holder[0] ?: throw IOException("החיבור למכשיר נסגר.")
    }

    /** Waits for data from [pump] without ever blocking on the library. Returns at end of stream. */
    private fun drain(pump: Pump, cancel: CancelToken?, idleMs: Long, onChunk: (ByteArray) -> Unit) {
        var lastData = System.currentTimeMillis()
        while (true) {
            val item = pump.queue.poll(250, TimeUnit.MILLISECONDS)
            if (cancel != null && cancel.cancelled) {
                pump.abandon()
                throw CancellationException("הפעולה בוטלה")
            }
            if (item == null) {
                if (System.currentTimeMillis() - lastData > idleMs) {
                    pump.abandon()
                    WirelessLink.linkDied("לא התקבלו נתונים מהמכשיר. החיבור כנראה נתקע.")
                    throw IOException("לא התקבלו נתונים מהמכשיר במשך ${idleMs / 1000} שניות. החיבור נתקע, נסה שוב.")
                }
                continue
            }
            lastData = System.currentTimeMillis()
            when (item) {
                is ByteArray -> onChunk(item)
                is Throwable -> {
                    pump.abandon()
                    throw IOException("הקריאה מהמכשיר נכשלה: ${item.message ?: item.javaClass.simpleName}")
                }
                else -> return
            }
        }
    }

    override fun exec(command: String): ExecResult = execInternal(command, null)

    private fun execInternal(command: String, cancel: CancelToken?): ExecResult {
        val stream = openBounded("exec:($command) 2>&1; echo $marker\$?", cancel)
        val pump = Pump(stream)
        val out = ByteArrayOutputStream()
        try {
            drain(pump, cancel, 120_000L) { chunk -> if (out.size() < 1024 * 1024) out.write(chunk, 0, chunk.size) }
        } finally {
            pump.abandon()
        }
        return splitMarker(out.toString("UTF-8"))
    }

    override fun pipeOut(command: String, sink: OutputStream, errors: StringBuilder?, cancel: CancelToken?): Int {
        val status = "/data/local/tmp/sb-" + UUID.randomUUID().toString().take(12)
        val wrapped = "($command) 2>${shellQuote("$status.err")}; echo \$? > ${shellQuote(status)}"
        val stream = openBounded("exec:$wrapped", cancel)
        val pump = Pump(stream)
        try {
            drain(pump, cancel, 90_000L) { chunk -> sink.write(chunk, 0, chunk.size) }
        } finally {
            pump.abandon()
        }
        val report = execInternal(
            "cat ${shellQuote(status)}; echo ---ERR---; head -c 2000 ${shellQuote("$status.err")}; " +
                "rm -f ${shellQuote(status)} ${shellQuote("$status.err")}",
            cancel
        ).output
        val parts = report.split("---ERR---", limit = 2)
        val code = parts[0].trim().toIntOrNull() ?: -1
        if (errors != null && parts.size > 1) errors.append(parts[1].trim())
        return code
    }

    override fun pipeIn(command: String, source: InputStream, output: StringBuilder?, cancel: CancelToken?): Int {
        val stream = openBounded("exec:($command) 2>&1; echo $marker\$?", cancel)
        val pump = Pump(stream)
        val written = AtomicLong(0L)
        // Writing can block too (the device applies flow control), so it has its own thread.
        val writer = thread(name = "wireless-write", isDaemon = true) {
            try {
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(chunk)
                    if (count < 0) break
                    stream.output.write(chunk, 0, count)
                    written.addAndGet(count.toLong())
                }
                stream.output.flush()
            } catch (_: Throwable) {
                // the command stopped reading (finished or failed); its output explains which
            }
        }
        val text = ByteArrayOutputStream()
        var lastWritten = -1L
        var lastProgress = System.currentTimeMillis()
        try {
            while (true) {
                val item = pump.queue.poll(250, TimeUnit.MILLISECONDS)
                if (cancel != null && cancel.cancelled) {
                    throw CancellationException("הפעולה בוטלה")
                }
                val now = System.currentTimeMillis()
                val total = written.get()
                if (total != lastWritten) {
                    lastWritten = total
                    lastProgress = now
                }
                if (item == null) {
                    if (now - lastProgress > 180_000L) {
                        WirelessLink.linkDied("המכשיר לא קלט נתונים. החיבור כנראה נתקע.")
                        throw IOException("המכשיר לא קלט נתונים במשך 3 דקות. החיבור נתקע, נסה שוב.")
                    }
                    continue
                }
                lastProgress = now
                if (item is ByteArray) {
                    if (text.size() < 64 * 1024) text.write(item, 0, item.size)
                } else {
                    break
                }
            }
        } finally {
            pump.abandon()
            writer.join(1_000L)
        }
        val result = splitMarker(text.toString("UTF-8"))
        if (output != null) output.append(result.output.take(8_000))
        return result.exitCode
    }

    private fun splitMarker(text: String): ExecResult {
        val index = text.lastIndexOf(marker)
        if (index < 0) return ExecResult(-1, text.trim())
        val code = text.substring(index + marker.length).trim().toIntOrNull() ?: -1
        return ExecResult(code, text.substring(0, index).trim())
    }
}
