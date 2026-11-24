package com.aw950aw.smartbackup.wireless

import java.io.InputStream
import java.io.OutputStream

/** One open ADB stream (a running command on the device). */
interface WirelessStream {
    val input: InputStream
    val output: OutputStream
    fun close()
}

/**
 * Minimal view of a wireless-ADB client library. The app only talks to this interface, so the
 * library itself lives in one optional file and a build without it still works (Shizuku / Root).
 */
interface WirelessApi {
    /** Pairs with the "Pair device with pairing code" dialog. */
    fun pair(host: String, port: Int, code: String): Boolean

    /** Opens the TLS connection to the Wireless Debugging port. */
    fun connect(host: String, port: Int): Boolean

    val connected: Boolean

    fun disconnect()

    /** For example "exec:ls /sdcard". */
    fun open(destination: String): WirelessStream
}
