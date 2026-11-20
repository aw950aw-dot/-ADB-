package com.draco.ladb.utils

/**
 * A command that actually ran on the device (through ADB or su) and finished with a
 * non-zero exit code. Keeping the exit code lets callers tell "directory does not exist"
 * (a normal situation) from a real failure.
 */
class RemoteCommandException(val exitCode: Int, message: String) : IllegalStateException(message)
