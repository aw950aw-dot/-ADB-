package com.aw950aw.smartbackup.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

sealed interface ShizukuState {
    data object Unknown : ShizukuState
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object NeedsPermission : ShizukuState
    data object PermissionDenied : ShizukuState
    data object Binding : ShizukuState
    data class Ready(val shell: PrivilegedShell) : ShizukuState
    data class Failed(val message: String) : ShizukuState
}

/**
 * Everything about Shizuku in one place: is it installed, running, did the user allow this app,
 * and the bound [PrivilegedService]. The UI only observes [state].
 */
object ShizukuLink {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4107
    private const val SERVICE_VERSION = 1

    private val mutableState = MutableStateFlow<ShizukuState>(ShizukuState.Unknown)
    val state: StateFlow<ShizukuState> = mutableState

    private var appContext: Context? = null
    private var registered = false
    private var binding = false
    private var connection: ServiceConnection? = null
    private var service: IPrivilegedService? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }

    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        connection = null
        binding = false
        refresh()
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                refresh()
            } else {
                mutableState.value = ShizukuState.PermissionDenied
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!registered) {
            registered = true
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
        refresh()
    }

    fun isInstalled(): Boolean {
        val context = appContext ?: return false
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Re-reads the situation. Cheap; call it whenever the screen comes back to the front. */
    fun refresh() {
        if (!isInstalled()) {
            mutableState.value = ShizukuState.NotInstalled
            return
        }
        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!alive) {
            service = null
            mutableState.value = ShizukuState.NotRunning
            return
        }
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 10) {
                mutableState.value = ShizukuState.Failed("גרסת Shizuku ישנה מדי. עדכן את Shizuku ונסה שוב.")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (mutableState.value !is ShizukuState.PermissionDenied) {
                    mutableState.value = ShizukuState.NeedsPermission
                }
                return
            }
        } catch (error: Throwable) {
            mutableState.value = ShizukuState.Failed(error.message ?: "שגיאה בבדיקת Shizuku")
            return
        }
        val existing = service
        if (existing != null && existing.asBinder().pingBinder()) {
            if (mutableState.value !is ShizukuState.Ready) {
                val uid = try { existing.whoAmI() } catch (_: Throwable) { -1 }
                mutableState.value = ShizukuState.Ready(ShizukuShell(existing, uid))
            }
            return
        }
        bind()
    }

    /** Shows Shizuku's permission dialog. If the user chose "deny always", Shizuku must be opened manually. */
    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                mutableState.value = ShizukuState.PermissionDenied
                return
            }
            mutableState.value = ShizukuState.NeedsPermission
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (error: Throwable) {
            mutableState.value = ShizukuState.Failed(error.message ?: "לא ניתן לבקש הרשאה מ-Shizuku")
        }
    }

    private fun bind() {
        val context = appContext ?: return
        if (binding) return
        binding = true
        mutableState.value = ShizukuState.Binding

        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PrivilegedService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("service")
            .version(SERVICE_VERSION)

        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                binding = false
                if (binder == null || !binder.pingBinder()) {
                    mutableState.value = ShizukuState.Failed("שירות Shizuku לא הגיב.")
                    return
                }
                val bound = IPrivilegedService.Stub.asInterface(binder)
                service = bound
                val uid = try { bound.whoAmI() } catch (_: Throwable) { -1 }
                mutableState.value = ShizukuState.Ready(ShizukuShell(bound, uid))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                binding = false
                refresh()
            }
        }
        connection = newConnection
        try {
            Shizuku.bindUserService(args, newConnection)
        } catch (error: Throwable) {
            binding = false
            mutableState.value = ShizukuState.Failed(error.message ?: "לא ניתן להפעיל את שירות Shizuku")
        }
    }
}
