package com.aw950aw.smartbackup.wireless.libadb

import android.content.Context
import android.os.Build
import com.aw950aw.smartbackup.wireless.AdbKeyStore
import com.aw950aw.smartbackup.wireless.AdbKeys
import com.aw950aw.smartbackup.wireless.WirelessApi
import com.aw950aw.smartbackup.wireless.WirelessStream
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * The ONLY file that touches the wireless-ADB library (libadb-android: pairing with the code,
 * TLS connect, ADB streams). Everything except the subclass below is called by name, so a small
 * difference between library versions shows up as a readable message in the in-app log rather than
 * as a build failure. The build workflow also has a fallback that builds without this folder.
 */
class LibAdbBackend(context: Context) : WirelessApi {

    private class Manager(private val keys: AdbKeys) : AbsAdbConnectionManager() {
        override fun getPrivateKey(): PrivateKey = keys.privateKey
        override fun getCertificate(): Certificate = keys.certificate
        override fun getDeviceName(): String = "SmartBackup"
    }

    private val manager: Manager = Manager(AdbKeyStore.load(context.applicationContext))

    init {
        // Needed so the library uses TLS (Wireless Debugging, Android 11+). Optional in some versions.
        optional("setApi", Build.VERSION.SDK_INT)
        optional("setTimeout", 20L, TimeUnit.SECONDS)
    }

    override fun pair(host: String, port: Int, code: String): Boolean =
        (call(manager, "pair", host, port, code) as? Boolean) ?: true

    override fun connect(host: String, port: Int): Boolean =
        (call(manager, "connect", host, port) as? Boolean) ?: false

    override val connected: Boolean
        get() = (call(manager, "isConnected") as? Boolean) ?: false

    override fun disconnect() {
        try {
            call(manager, "disconnect")
        } catch (_: Throwable) {
        }
    }

    override fun open(destination: String): WirelessStream {
        val stream = call(manager, "openStream", destination)
            ?: throw IllegalStateException("הספרייה לא החזירה stream")
        val inStream = call(stream, "openInputStream") as InputStream
        val outStream = call(stream, "openOutputStream") as OutputStream
        return object : WirelessStream {
            override val input: InputStream = inStream
            override val output: OutputStream = outStream
            override fun close() {
                try {
                    call(stream, "close")
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun optional(name: String, vararg args: Any?) {
        try {
            call(manager, name, *args)
        } catch (_: Throwable) {
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type.name) {
        "int" -> Int::class.javaObjectType
        "long" -> Long::class.javaObjectType
        "boolean" -> Boolean::class.javaObjectType
        else -> type
    }

    /** Calls a public method by name with matching argument types. */
    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.indices.all { index ->
                    val type = candidate.parameterTypes[index]
                    val argument = args[index]
                    if (argument == null) !type.isPrimitive else boxed(type).isInstance(argument)
                }
        } ?: throw NoSuchMethodException("בספריית ה-ADB אין את הפעולה $name(${args.size} פרמטרים)")
        try {
            return method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }
}
