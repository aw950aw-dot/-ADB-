package com.aw950aw.smartbackup.wireless

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fast check of a Wireless Debugging connect port that does not depend on any ADB library:
 * plain TCP, then the first ADB message (CNXN). A healthy device answers within milliseconds
 * (STLS or AUTH). This separates "the device side is stuck" from "our client has a problem".
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
                header.putInt(A_CNXN)
                header.putInt(0x01000001)
                header.putInt(256 * 1024)
                header.putInt(payload.size)
                header.putInt(0)
                header.putInt(A_CNXN.inv())
                val out = socket.getOutputStream()
                out.write(header.array())
                out.write(payload)
                out.flush()
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
                if (command == A_STLS || command == A_AUTH || command == A_CNXN) Result.RESPONDS else Result.NO_REPLY
            }
        } catch (_: SocketTimeoutException) {
            Result.NO_REPLY
        } catch (_: Exception) {
            Result.UNREACHABLE
        }
    }
}
