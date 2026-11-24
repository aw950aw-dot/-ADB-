package com.aw950aw.smartbackup.shell

import java.util.concurrent.CancellationException

/** Lets the user stop a long running backup or restore. */
class CancelToken {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun check() {
        if (cancelled) throw CancellationException("הפעולה בוטלה")
    }
}
