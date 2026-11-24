package com.aw950aw.smartbackup.shell

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** [PrivilegedShell] backed by `su`. Only used when the device is rooted and the user allows it. */
class RootShell : PrivilegedShell {
    override val label: String = "Root"
    override val uid: Int = 0

    override fun exec(command: String): ExecResult {
        val out = ByteArrayOutputStream()
        val code = ShellCore.run(listOf("su", "-c", "($command) 2>&1"), null, out, null)
        return ExecResult(code, out.toString("UTF-8"))
    }

    override fun pipeOut(command: String, sink: OutputStream, errors: StringBuilder?, cancel: CancelToken?): Int {
        val err = ByteArrayOutputStream()
        val code = ShellCore.run(listOf("su", "-c", command), null, sink, err, cancel)
        if (errors != null) errors.append(err.toString("UTF-8").take(8_000))
        cancel?.check()
        return code
    }

    override fun pipeIn(command: String, source: InputStream, output: StringBuilder?, cancel: CancelToken?): Int {
        val out = ByteArrayOutputStream()
        val code = ShellCore.run(listOf("su", "-c", "($command) 2>&1"), source, out, null, cancel)
        if (output != null) output.append(out.toString("UTF-8").take(8_000))
        cancel?.check()
        return code
    }

    companion object {
        /** Returns a root shell if `su` works and answers uid 0, otherwise null. May show a root permission prompt. */
        fun probe(): RootShell? {
            return try {
                val out = ByteArrayOutputStream()
                val code = ShellCore.run(listOf("su", "-c", "id -u"), null, out, null)
                if (code == 0 && out.toString("UTF-8").trim() == "0") RootShell() else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
