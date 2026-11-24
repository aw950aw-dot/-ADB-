package com.aw950aw.smartbackup.shell

import java.io.InputStream
import java.io.OutputStream

data class ExecResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * A shell with more privileges than a normal app (shell uid through Shizuku, or root).
 * Every implementation hands commands to `sh -c`, so a command is always ONE string.
 */
interface PrivilegedShell {
    /** Text for the user, for example "Shizuku (shell, uid 2000)". */
    val label: String
    val uid: Int
    val isRoot: Boolean get() = uid == 0

    /** Runs a short command and returns stdout and stderr together. */
    fun exec(command: String): ExecResult

    /** Runs [command] and copies its stdout into [sink]. stderr text is appended to [errors]. Returns the exit code. */
    fun pipeOut(command: String, sink: OutputStream, errors: StringBuilder? = null, cancel: CancelToken? = null): Int

    /** Runs [command] and feeds [source] to its stdin. stdout+stderr text is appended to [output]. Returns the exit code. */
    fun pipeIn(command: String, source: InputStream, output: StringBuilder? = null, cancel: CancelToken? = null): Int
}

/** Quotes a value for safe use inside a shell command. */
fun shellQuote(value: String): String = "\u0027" + value.replace("\u0027", "\u0027\\\u0027\u0027") + "\u0027"
