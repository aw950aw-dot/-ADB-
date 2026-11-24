package com.aw950aw.smartbackup.wireless

import android.content.Context
import com.aw950aw.smartbackup.shell.PrivilegedShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed interface WirelessState {
    data object Idle : WirelessState
    data object WaitingForPairingDialog : WirelessState
    data class WaitingForCode(val host: String, val port: Int) : WirelessState
    data class Busy(val message: String) : WirelessState
    data class Ready(val shell: PrivilegedShell) : WirelessState
    data class Failed(val message: String) : WirelessState
}

/**
 * The app's own wireless-ADB connection: pair once with the code, then connect whenever
 * Wireless Debugging is on. All steps are logged, so a failure always says where it stopped.
 */
object WirelessLink {
    private const val PREFS = "wireless_link"
    private const val KEY_PAIRED = "paired"
    private const val BACKEND_CLASS = "com.aw950aw.smartbackup.wireless.libadb.LibAdbBackend"

    private val stateFlow = MutableStateFlow<WirelessState>(WirelessState.Idle)
    val state: StateFlow<WirelessState> = stateFlow

    private val logFlow = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = logFlow

    private var appContext: Context? = null
    private var api: WirelessApi? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setState(state: WirelessState) {
        stateFlow.value = state
    }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$time  $message"
        synchronized(this) {
            val updated = logFlow.value + line
            logFlow.value = if (updated.size > 80) updated.takeLast(80) else updated
        }
    }

    fun clearLog() {
        logFlow.value = emptyList()
    }

    /** False in the fallback build, which was compiled without the wireless-ADB library. */
    fun backendAvailable(): Boolean = try {
        Class.forName(BACKEND_CLASS)
        true
    } catch (_: Throwable) {
        false
    }

    fun isPaired(): Boolean =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_PAIRED, false) ?: false

    private fun markPaired(value: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_PAIRED, value)?.apply()
    }

    private fun backend(): WirelessApi {
        api?.let { return it }
        val context = appContext ?: throw IllegalStateException("האפליקציה לא אותחלה.")
        val created = try {
            Class.forName(BACKEND_CLASS).getConstructor(Context::class.java).newInstance(context) as WirelessApi
        } catch (error: ClassNotFoundException) {
            throw IllegalStateException("גרסה זו נבנתה בלי רכיב החיבור הישיר. השתמש ב-Shizuku או ב-Root.")
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            throw IllegalStateException("רכיב החיבור הישיר לא עלה: ${cause.javaClass.simpleName}: ${cause.message}")
        }
        api = created
        return created
    }

    /** Runs [block] but gives up after [timeoutMs]. The blocked call is abandoned (and the link is dropped by the caller). */
    private fun <T> withTimeout(timeoutMs: Long, block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(Callable { block() })
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                throw IllegalStateException("אין תגובה תוך ${timeoutMs / 1000} שניות.")
            } catch (error: java.util.concurrent.ExecutionException) {
                throw error.cause ?: error
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** Pairs with the code, then connects. Blocking; call from a worker thread. */
    fun pairAndConnect(host: String, port: Int, code: String): Boolean {
        try {
            setState(WirelessState.Busy("מבצע התאמה מול $host:$port…"))
            log("מתחיל התאמה מול $host:$port")
            val library = backend()
            val paired = withTimeout(30_000L) { library.pair(host, port, code) }
            if (!paired) {
                fail("ההתאמה נדחתה. בדוק שהקוד נכון ועדיין מוצג בחלון, ונסה שוב.")
                return false
            }
            log("ההתאמה הצליחה")
            markPaired(true)
            return connect()
        } catch (error: Throwable) {
            fail(describe(error))
            return false
        }
    }

    /** Finds the connect port through mDNS and connects. Blocking; call from a worker thread. */
    fun connect(): Boolean {
        val context = appContext ?: return false
        try {
            setState(WirelessState.Busy("מחפש את שירות החיבור של המכשיר…"))
            log("מחפש שירות חיבור (mDNS)")
            val endpoint = MdnsDiscovery(context).find(MdnsDiscovery.CONNECT_TYPE, 10_000L) { log(it) }
            if (endpoint == null) {
                fail("לא נמצא שירות חיבור. ודא ש'ניפוי באגים אלחוטי' דלוק ושהמכשיר מחובר ל-Wi-Fi.")
                return false
            }
            log("נמצא שירות חיבור: ${endpoint.host}:${endpoint.port}")

            val library = backend()
            val hosts = ArrayList<String>()
            hosts.add(endpoint.host)
            if (!hosts.contains("127.0.0.1")) hosts.add("127.0.0.1")

            var connected = false
            for (host in hosts) {
                val probe = AdbProbe.check(host, endpoint.port)
                log("בדיקת פורט $host:${endpoint.port}: $probe")
                if (probe == AdbProbe.Result.REFUSED || probe == AdbProbe.Result.UNREACHABLE) continue
                setState(WirelessState.Busy("מתחבר אל $host:${endpoint.port}…"))
                try {
                    connected = withTimeout(20_000L) { library.connect(host, endpoint.port) }
                } catch (error: Throwable) {
                    log("חיבור אל $host נכשל: ${describe(error)}")
                    try { library.disconnect() } catch (_: Throwable) {}
                }
                if (connected) break
            }
            if (!connected) {
                fail("החיבור למכשיר נכשל. אם ההתאמה נעשתה, כבה והדלק 'ניפוי באגים אלחוטי' ונסה שוב; אחרת בצע התאמה מחדש.")
                return false
            }
            log("מחובר. בודק הרשאות…")
            val probeShell = WirelessShell(library, -1)
            val uid = probeShell.exec("id -u").output.trim().toIntOrNull() ?: -1
            if (uid < 0) {
                fail("החיבור נוצר אך הפקודה הראשונה לא הצליחה.")
                return false
            }
            log("מוכן. הרשאה: uid $uid")
            setState(WirelessState.Ready(WirelessShell(library, uid)))
            return true
        } catch (error: Throwable) {
            fail(describe(error))
            return false
        }
    }

    fun disconnect() {
        try {
            api?.disconnect()
        } catch (_: Throwable) {
        }
        setState(WirelessState.Idle)
        log("נותק")
    }

    /** Forgets the key and the "paired" mark. The next connection needs pairing again. */
    fun forgetPairing() {
        disconnect()
        appContext?.let { AdbKeyStore.reset(it) }
        api = null
        markPaired(false)
        log("ההתאמה נמחקה")
    }

    private fun fail(message: String) {
        log("נכשל: $message")
        setState(WirelessState.Failed(message))
    }

    private fun describe(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val text = root.message ?: root.javaClass.simpleName
        return if (root === error) text else "${error.javaClass.simpleName}: $text"
    }
}
