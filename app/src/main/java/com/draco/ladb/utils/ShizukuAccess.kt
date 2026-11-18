package com.draco.ladb.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.draco.ladb.shizuku.IUserShellService
import com.draco.ladb.shizuku.ShizukuShellService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Optional shell backend provided by the user's own Shizuku installation.
 * No commands run until Shizuku is alive and the user has granted this app
 * Shizuku permission. On ordinary Shizuku installations the process UID is shell (2000);
 * rooted Shizuku may expose UID 0.
 */
object ShizukuAccess {
    private const val TAG = "ADBShizuku"
    private const val REQUEST_CODE = 7301
    private val lock = Any()
    private val callbacks = CopyOnWriteArrayList<(Boolean, String) -> Unit>()

    @Volatile private var appContext: Context? = null
    @Volatile private var remote: IUserShellService? = null
    @Volatile private var serviceConnection: ServiceConnection? = null
    @Volatile private var binding = false
    @Volatile private var permissionRequestPending = false
    @Volatile private var initialized = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        synchronized(lock) {
            remote = null
            binding = false
            serviceConnection = null
        }
        appContext?.let { runCatching { ADB.getInstance(it).deactivateShizukuBackend() } }
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            permissionRequestPending = false
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                complete(false, "המשתמש לא אישר ל-ADB SMART גישה דרך Shizuku.")
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            initialized = true
        }
    }

    fun isInstalled(context: Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    "moe.shizuku.privileged.api",
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isConnected(): Boolean = remote != null && isBinderAlive() && hasPermission()

    fun serverUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun connect(context: Context, callback: (Boolean, String) -> Unit) {
        initialize(context)
        if (!isBinderAlive()) {
            callback(false, if (isInstalled(context)) {
                "Shizuku מותקן אך אינו פעיל. פתח את Shizuku והפעל את השירות, ואז נסה שוב."
            } else {
                "Shizuku אינו מותקן. אפשר להמשיך להתחבר באמצעות Wireless Debugging."
            })
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            callback(false, "גרסת Shizuku ישנה מדי. נדרשת גרסה 11 ומעלה.")
            return
        }
        if (isConnected()) {
            callback(true, identityMessage())
            return
        }

        callbacks.add(callback)
        if (hasPermission()) {
            bindUserService()
            return
        }
        synchronized(lock) {
            if (permissionRequestPending) return
            permissionRequestPending = true
        }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (error: Exception) {
            permissionRequestPending = false
            complete(false, "לא ניתן לבקש הרשאת Shizuku: " + (error.message ?: "שגיאה"))
        }
    }

    private fun bindUserService() {
        val context = appContext ?: run {
            complete(false, "Shizuku לא אותחל כראוי.")
            return
        }
        synchronized(lock) {
            if (remote != null) {
                complete(true, identityMessage())
                return
            }
            if (binding) return
            binding = true
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .tag("adb-smart-shell")
            .version(1)
            .processNameSuffix("adbsmart")

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service
                if (binder == null) {
                    synchronized(lock) { binding = false; remote = null; serviceConnection = null }
                    complete(false, "Shizuku לא החזיר חיבור לשירות הפקודות.")
                    return
                }
                val shell = IUserShellService.Stub.asInterface(binder)
                if (shell == null) {
                    synchronized(lock) { binding = false; remote = null; serviceConnection = null }
                    complete(false, "Shizuku התחבר אך שירות הפקודות לא נטען.")
                    return
                }
                synchronized(lock) {
                    remote = shell
                    binding = false
                    serviceConnection = this
                }
                Thread {
                    val check = runCatching { shell.execute("id", 5_000L) }
                    if (check.isSuccess) {
                        complete(true, identityMessage())
                    } else {
                        synchronized(lock) { remote = null; serviceConnection = null }
                        complete(false, "שירות Shizuku הופעל אך בדיקת ה-shell נכשלה: " +
                            (check.exceptionOrNull()?.message ?: "שגיאה"))
                    }
                }.apply { isDaemon = true; name = "ShizukuShellVerify"; start() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) {
                    remote = null
                    binding = false
                    serviceConnection = null
                }
                appContext?.let { runCatching { ADB.getInstance(it).deactivateShizukuBackend() } }
                Log.w(TAG, "Shizuku UserService disconnected")
            }
        }

        try {
            Shizuku.bindUserService(args, connection)
        } catch (error: Exception) {
            synchronized(lock) { binding = false; serviceConnection = null; remote = null }
            complete(false, "הפעלת שירות Shizuku נכשלה: " + (error.message ?: "שגיאה"))
        }
    }

    fun executeShell(command: String, timeoutSeconds: Long = 30): ADB.CommandResult {
        require(command.isNotBlank() && command.length <= 12_000) { "פקודת shell לא תקינה" }
        val shell = remote ?: throw IllegalStateException("אין הרשאת Shizuku פעילה")
        if (!isBinderAlive() || !hasPermission()) {
            throw IllegalStateException("חיבור Shizuku אבד. פתח את Shizuku ואשר מחדש.")
        }
        val raw = shell.execute(command, timeoutSeconds.coerceIn(1L, 120L) * 1_000L)
        val separator = raw.indexOf('\n')
        if (separator < 0) {
            throw IllegalStateException(raw.ifBlank { "שירות Shizuku לא החזיר תשובה" })
        }
        val exitCode = raw.substring(0, separator).trim().toIntOrNull()
            ?: throw IllegalStateException("תשובת Shizuku אינה תקינה: " + raw.take(300))
        return ADB.CommandResult(exitCode, raw.substring(separator + 1).trim())
    }

    fun disconnect() {
        val context = appContext ?: return
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellService::class.java.name)
        ).daemon(false).tag("adb-smart-shell").version(1).processNameSuffix("adbsmart")
        val connection = synchronized(lock) {
            remote = null
            binding = false
            serviceConnection.also { serviceConnection = null }
        }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        complete(false, "חיבור Shizuku נותק.")
    }

    private fun identityMessage(): String {
        val uid = serverUid()
        val identity = when (uid) {
            0 -> "Root (UID 0)"
            2000 -> "Android Shell (UID 2000)"
            else -> "UID $uid"
        }
        return "מחובר דרך Shizuku — $identity"
    }

    private fun complete(success: Boolean, message: String) {
        val waiting = callbacks.toList()
        callbacks.clear()
        waiting.forEach { callback -> runCatching { callback(success, message) } }
    }
}
