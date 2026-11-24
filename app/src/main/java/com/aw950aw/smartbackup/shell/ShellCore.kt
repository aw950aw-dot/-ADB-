package com.aw950aw.smartbackup.shell

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/** Starts a process and pumps its streams. Used inside the Shizuku service and for the root shell. */
object ShellCore {
    fun run(
        argv: List<String>,
        stdin: InputStream?,
        stdout: OutputStream?,
        stderr: OutputStream?,
        cancel: CancelToken? = null
    ): Int {
        val process = ProcessBuilder(argv).start()
        val pumps = ArrayList<Thread>()
        pumps.add(thread(name = "shell-out", isDaemon = true) { pump(process, process.inputStream, stdout) })
        pumps.add(thread(name = "shell-err", isDaemon = true) { pump(process, process.errorStream, stderr) })

        var feeder: Thread? = null
        if (stdin != null) {
            feeder = thread(name = "shell-in", isDaemon = true) {
                try {
                    stdin.copyTo(process.outputStream)
                } catch (_: IOException) {
                    // the process ended before it read everything
                } finally {
                    try {
                        process.outputStream.close()
                    } catch (_: IOException) {
                    }
                }
            }
        } else {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
            }
        }

        var watcher: Thread? = null
        if (cancel != null) {
            watcher = thread(name = "shell-cancel", isDaemon = true) {
                while (process.isAlive) {
                    if (cancel.cancelled) {
                        process.destroyForcibly()
                        break
                    }
                    try {
                        Thread.sleep(150)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        val code = process.waitFor()
        for (pump in pumps) pump.join()
        watcher?.interrupt()
        feeder?.join(2_000)
        return code
    }

    private fun pump(process: Process, input: InputStream, output: OutputStream?) {
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output != null) {
                    try {
                        output.write(buffer, 0, count)
                    } catch (_: IOException) {
                        // the receiver is gone: stop the process instead of letting it block forever
                        process.destroyForcibly()
                        return
                    }
                }
            }
            output?.flush()
        } catch (_: IOException) {
            // process stream closed
        }
    }
}
