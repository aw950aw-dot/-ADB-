package com.draco.ladb.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.draco.ladb.BuildConfig
import com.draco.ladb.shizuku.IUserShellService
import com.draco.ladb.shizuku.ShizukuShellService
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Optional shell backend provided by the user's own Shizuku installation.
 * It is enabled only after Shizuku itself confirms the user's permission.
 */
object ShizukuAccess {
    private const val TAG = "ADBShizuku"
    private const val REQUEST_CODE = 7301
    private const val SERVICE_BIND_TIMEOUT_MS = 25_000L

    private val lock = Any()
    private val callbacks = CopyOnWriteArrayList<(Boolean, String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    @Volatile private var remote: IUserShellService? = null
    @Volatile private var serviceConnection: ServiceConnection? = null
    @Volatile private var binding = false
    @Volatile private var permissionRequestPending = false
    @Volatile private var initialized = false
    @Volatile private var bindingGeneration = 0
    @Volatile private var remoteUid = -1

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received; server API=${runCatching { Shizuku.getVersion() }.getOrDefault(-1)}")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        synchronized(lock) {
            remote = null
            remoteUid = -1
            binding = false
            serviceConnection = null
            bindingGeneration++
        }
        mainHandler.removeCallbacksAndMessages(null)
        appContext?.let { runCatching { ADB.getInstance(it).deactivateShizukuBackend() } }
        complete(false, "שירות Shizuku נסגר. הפעל אותו מחדש ונסה להתחבר שוב.")
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            permissionRequestPending = false
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                complete(false, "לא אושרה ל-ADB SMART הרשאת Shizuku.")
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
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.getVersion() >= 10 &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isConnected(): Boolean = remote != null && isBinderAlive() && hasPermission()

    fun serverUid(): Int = remoteUid.takeIf { it >= 0 }
        ?: runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun connect(context: Context, callback: (Boolean, String) -> Unit) {
        initialize(context)

        if (!isBinderAlive()) {
            callback(false, if (isInstalled(context)) {
                "Shizuku מותקן אך השירות שלו אינו פעיל. פתח את Shizuku והפעל את השירות, ואז נסה שוב."
            } else {
                "Shizuku אינו מותקן. אפשר להתחבר במקום זאת באמצעות Wireless Debugging."
            })
            return
        }

        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true) || version < 10) {
            callback(false, "גרסת שירות Shizuku ישנה מדי (API $version). נדרשת גרסה 10 ומעלה.")
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
            complete(false, "בקשת ההרשאה של Shizuku נכשלה: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .tag("adb-smart-shell")
            // Incrementing with the app version forces Shizuku to recreate an old UserService after an update.
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("adbsmart")
            .debuggable(BuildConfig.DEBUG)

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
            remoteUid = -1
            bindingGeneration++
        }

        val generation = bindingGeneration
        val args = userServiceArgs(context)

        val timeout = Runnable {
            val didTimeout = synchronized(lock) {
                if (generation == bindingGeneration && binding) {
                    binding = false
                    remote = null
                    remoteUid = -1
                    serviceConnection = null
                    true
                } else false
            }
            if (didTimeout) {
                Log.e(TAG, "Shizuku UserService did not connect within ${SERVICE_BIND_TIMEOUT_MS}ms")
                runCatching { Shizuku.unbindUserService(args, null, true) }
                complete(
                    false,
                    "Shizuku לא הצליח להפעיל את שירות הפקודות בתוך 25 שניות. " +
                        "בדוק שהשירות הראשי של Shizuku פעיל ועדכן את Shizuku לגרסה עדכנית."
                )
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                mainHandler.removeCallbacks(timeout)
                if (service == null || !service.pingBinder()) {
                    failBinding(generation, "Shizuku התחיל את השירות אך לא החזיר חיבור תקין.")
                    return
                }

                val shell = IUserShellService.Stub.asInterface(service)
                if (shell == null) {
                    failBinding(generation, "לא ניתן לקרוא את ממשק שירות הפקודות של Shizuku.")
                    return
                }

                synchronized(lock) {
                    if (generation != bindingGeneration) return
                    remote = shell
                    binding = false
                    serviceConnection = this
                }

                val verifyThread = Thread {
                    val check = runCatching { shell.execute("/system/bin/id", 5_000L) }
                    if (check.isFailure) {
                        failBinding(
                            generation,
                            "שירות Shizuku התחיל אך בדיקת shell נכשלה: " +
                                (check.exceptionOrNull()?.message ?: "שגיאה לא ידועה")
                        )
                        return@Thread
                    }

                    val raw = check.getOrThrow()
                    val separator = raw.indexOf('\n')
                    val exitCode = if (separator >= 0) raw.substring(0, separator).trim().toIntOrNull() else null
                    val identity = if (separator >= 0) raw.substring(separator + 1) else ""
                    val uid = Regex("""uid=(\d+)""").find(identity)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()

                    if (exitCode != 0 || uid == null) {
                        failBinding(
                            generation,
                            "שירות Shizuku לא החזיר זהות shell תקינה. תשובת בדיקה: " +
                                raw.take(500).ifBlank { "ריקה" }
                        )
                        return@Thread
                    }

                    synchronized(lock) {
                        if (generation != bindingGeneration) return@Thread
                        remoteUid = uid
                    }
                    Log.i(TAG, "Shizuku UserService verified with uid=$uid: ${identity.trim()}")
                    complete(true, identityMessage())
                }
                verifyThread.isDaemon = true
                verifyThread.name = "ShizukuShellVerify"
                verifyThread.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                mainHandler.removeCallbacks(timeout)
                synchronized(lock) {
                    if (generation != bindingGeneration) return
                    remote = null
                    remoteUid = -1
                    binding = false
                    serviceConnection = null
                }
                appContext?.let { runCatching { ADB.getInstance(it).deactivateShizukuBackend() } }
                complete(false, "שירות Shizuku נותק. בדוק שהשירות עדיין פועל.")
                Log.w(TAG, "Shizuku UserService disconnected")
            }
        }

        synchronized(lock) {
            if (generation == bindingGeneration) serviceConnection = connection
        }

        mainHandler.postDelayed(timeout, SERVICE_BIND_TIMEOUT_MS)

        try {
            Shizuku.bindUserService(args, connection)
        } catch (error: Exception) {
            mainHandler.removeCallbacks(timeout)
            failBinding(
                generation,
                "הפעלת שירות Shizuku נכשלה: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun failBinding(generation: Int, message: String) {
        synchronized(lock) {
            if (generation != bindingGeneration) return
            binding = false
            remote = null
            remoteUid = -1
            serviceConnection = null
        }
        mainHandler.removeCallbacksAndMessages(null)
        Log.e(TAG, message)
        complete(false, message)
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
        val args = userServiceArgs(context)
        val connection = synchronized(lock) {
            bindingGeneration++
            remote = null
            remoteUid = -1
            binding = false
            serviceConnection.also { serviceConnection = null }
        }
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        appContext?.let { runCatching { ADB.getInstance(it).deactivateShizukuBackend() } }
        complete(false, "חיבור Shizuku נותק.")
    }

    private fun identityMessage(): String {
        val uid = serverUid()
        val identity = when (uid) {
            0 -> "Root (UID 0)"
            2000 -> "Android Shell (UID 2000)"
            -1 -> "UID לא ידוע"
            else -> "UID $uid"
        }
        return "מחובר דרך Shizuku — $identity"
    }

    private fun complete(success: Boolean, message: String) {
        if (callbacks.isEmpty()) return
        val waiting = callbacks.toList()
        callbacks.clear()
        waiting.forEach { callback -> runCatching { callback(success, message) } }
    }
}
