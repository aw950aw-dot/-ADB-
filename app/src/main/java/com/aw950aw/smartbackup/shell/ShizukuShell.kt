package com.aw950aw.smartbackup.shell

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/** [PrivilegedShell] that talks to [PrivilegedService] through Shizuku. Data moves through pipes, never through Binder buffers. */
class ShizukuShell(private val service: IPrivilegedService, override val uid: Int) : PrivilegedShell {

    override val label: String
        get() = if (uid == 0) "Shizuku (root)" else "Shizuku (shell, uid $uid)"

    override fun exec(command: String): ExecResult {
        val out = ByteArrayOutputStream()
        val code = pipeOut("($command) 2>&1", out, null, null)
        return ExecResult(code, out.toString("UTF-8"))
    }

    override fun pipeOut(command: String, sink: OutputStream, errors: StringBuilder?, cancel: CancelToken?): Int {
        val outPipe = ParcelFileDescriptor.createPipe()
        val errPipe = ParcelFileDescriptor.createPipe()
        val readOut = outPipe[0]
        val writeOut = outPipe[1]
        val readErr = errPipe[0]
        val writeErr = errPipe[1]

        var copyFailure: Throwable? = null
        val copier = thread(name = "pipe-out", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readOut).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel != null && cancel.cancelled) break
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                }
            } catch (error: Throwable) {
                copyFailure = error
                try { readOut.close() } catch (_: IOException) {}
            }
        }
        val errorReader = thread(name = "pipe-err", isDaemon = true) {
            drainText(readErr, errors)
        }

        val code = try {
            service.run(command, null, writeOut, writeErr)
        } finally {
            try { writeOut.close() } catch (_: IOException) {}
            try { writeErr.close() } catch (_: IOException) {}
        }
        copier.join()
        errorReader.join()
        val failure = copyFailure
        if (failure != null) throw failure
        cancel?.check()
        return code
    }

    override fun pipeIn(command: String, source: InputStream, output: StringBuilder?, cancel: CancelToken?): Int {
        val inPipe = ParcelFileDescriptor.createPipe()
        val outPipe = ParcelFileDescriptor.createPipe()
        val readIn = inPipe[0]
        val writeIn = inPipe[1]
        val readOut = outPipe[0]
        val writeOut = outPipe[1]

        val feeder = thread(name = "pipe-in", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeIn).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel != null && cancel.cancelled) break
                        val count = source.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
            } catch (_: IOException) {
                // the command stopped reading (finished or failed)
            }
        }
        val outputReader = thread(name = "pipe-in-out", isDaemon = true) {
            drainText(readOut, output)
        }

        val code = try {
            service.run("($command) 2>&1", readIn, writeOut, null)
        } finally {
            // Closing our copy of the read end unblocks the feeder if the command ended early.
            try { readIn.close() } catch (_: IOException) {}
            try { writeOut.close() } catch (_: IOException) {}
        }
        feeder.join()
        outputReader.join()
        cancel?.check()
        return code
    }

    private fun drainText(descriptor: ParcelFileDescriptor, target: StringBuilder?) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (target != null) {
                        synchronized(target) {
                            if (target.length < 8_000) target.append(String(buffer, 0, count, Charsets.UTF_8))
                        }
                    }
                }
            }
        } catch (_: IOException) {
        }
    }
}
