package com.aw950aw.smartbackup.shell

import android.os.ParcelFileDescriptor

/**
 * Runs inside the process that Shizuku starts, so commands get its privileges (normally shell).
 * Shizuku creates this class itself; it is not an Android component.
 */
class PrivilegedService : IPrivilegedService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun whoAmI(): Int = android.os.Process.myUid()

    override fun run(
        command: String,
        stdin: ParcelFileDescriptor?,
        stdout: ParcelFileDescriptor?,
        stderr: ParcelFileDescriptor?
    ): Int {
        val input = if (stdin != null) ParcelFileDescriptor.AutoCloseInputStream(stdin) else null
        val output = if (stdout != null) ParcelFileDescriptor.AutoCloseOutputStream(stdout) else null
        val errors = if (stderr != null) ParcelFileDescriptor.AutoCloseOutputStream(stderr) else null
        try {
            return ShellCore.run(listOf("/system/bin/sh", "-c", command), input, output, errors)
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            try { errors?.close() } catch (_: Exception) {}
        }
    }
}
