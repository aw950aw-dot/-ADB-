package com.draco.ladb.utils

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fast, adb-independent check of a Wireless Debugging connect port: it opens a plain TCP socket
 * and sends the first ADB message (CNXN). A healthy adbd answers within milliseconds (with STLS or
 * AUTH). That tells "device side is fine" apart from "device side is stuck" in about two seconds,
 * instead of letting the adb client hang for a long time.
 */
object AdbProbe {
    enum class Result { RESPONDS, NO_REPLY, REFUSED, UNREACHABLE }

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_STLS = 0x534c5453

    fun check(host: String, port: Int, timeoutMs: Int = 2_000): Result {
        return try {
            Socket().use { socket ->
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                } catch (_: ConnectException) {
                    return Result.REFUSED
                } catch (_: SocketTimeoutException) {
                    return Result.UNREACHABLE
                }
                socket.soTimeout = timeoutMs
                val payload = "host::\u0000".toByteArray(Charsets.US_ASCII)
                val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(A_CNXN)          // command
                header.putInt(0x01000001)      // protocol version (checksum no longer verified)
                header.putInt(256 * 1024)      // max payload
                header.putInt(payload.size)    // payload length
                header.putInt(0)               // payload checksum
                header.putInt(A_CNXN.inv())    // magic
                socket.getOutputStream().apply {
                    write(header.array())
                    write(payload)
                    flush()
                }
                val reply = ByteArray(24)
                var read = 0
                val input = socket.getInputStream()
                while (read < reply.size) {
                    val count = input.read(reply, read, reply.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read < reply.size) return Result.NO_REPLY
                val command = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).int
                if (command == A_STLS || command == A_AUTH || command == A_CNXN) Result.RESPONDS
                else Result.NO_REPLY
            }
        } catch (_: SocketTimeoutException) {
            Result.NO_REPLY
        } catch (_: Exception) {
            Result.UNREACHABLE
        }
    }
}
