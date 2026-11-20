package com.draco.ladb.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ADB(private val context: Context) {
    companion object {
        const val MAX_OUTPUT_BUFFER_SIZE = 512 * 1024
        const val OUTPUT_BUFFER_DELAY_MS = 250L

        /** Total budget for start-server + connect + shell verification of one connection attempt. */
        const val CONNECT_BUDGET_MS = 30_000L

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ADB? = null
        fun getInstance(context: Context): ADB = instance ?: synchronized(this) {
            instance ?: ADB(context.applicationContext).also { instance = it }
        }
    }

    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"
    private val scriptPath = "${context.getExternalFilesDir(null)}/script.sh"
    private val adbHomeDirectory = context.filesDir
    private val adbKeyDirectory = File(adbHomeDirectory, ".android")
    private val adbPrivateKeyFile = File(adbKeyDirectory, "adbkey")

    /** The private host key is the identity Android remembers after Wireless Debugging pairing. */
    fun hasAdbHostKey(): Boolean = adbPrivateKeyFile.isFile && adbPrivateKeyFile.length() > 0L

    private fun prepareAdbHome() {
        if (!adbKeyDirectory.exists() && !adbKeyDirectory.mkdirs()) {
            throw IllegalStateException("לא ניתן ליצור תיקיית מפתח ADB.")
        }
    }

    /**
     * Is the shell ready to handle commands?
     */
    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    @Volatile private var tryingToPair = false

    /**
     * Serial of the device that every non-interactive adb call must target. It is passed to the
     * adb client through ANDROID_SERIAL so that a second device entry (for example an mDNS
     * auto-connected duplicate) can never cause "more than one device/emulator".
     */
    @Volatile private var activeSerial: String? = null
    @Volatile private var shizukuBackendEnabled = false

    /**
     * Is the shell closed for any reason?
     */
    private val _closed = MutableLiveData(false)
    val closed: LiveData<Boolean> = _closed

    /**
     * Where shell output is stored
     */
    val outputBufferFile: File = File(context.cacheDir, "adb-smart-shell-output.log").apply {
        parentFile?.mkdirs()
        if (!exists()) createNewFile()
    }

    /**
     * Single shell instance where we can pipe commands to
     */
    private var shellProcess: Process? = null
    private val deathMonitorStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Returns the user buffer size if valid, else the default
     */
    fun getOutputBufferSize(): Int {
        val userValue = sharedPrefs.getString(context.getString(R.string.buffer_size_key), "524288") ?: "524288"
        return try {
            Integer.parseInt(userValue).coerceIn(32 * 1024, 4 * 1024 * 1024)
        } catch (_: NumberFormatException) {
            512 * 1024
        }
    }

    /**
     * Get a list of connected devices.
     */
    fun getDevices(timeoutSeconds: Long = 5): List<String> {
        val process = processForAdb(listOf("devices"), mergeError = true)
        val (exitCode, output) = captureProcessOutput(process, timeoutSeconds, "בדיקת התקני ADB חרגה מזמן ההמתנה.")
        if (exitCode != 0) throw IllegalStateException(output.ifBlank { "לא ניתן לקרוא את רשימת התקני ADB." })
        val deviceNames = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1] == "device") parts[0] else null
        }.toList()
        deviceNames.forEach { Log.d("ADB", "device=$it") }
        return deviceNames
    }

    /**
     * Start the ADB server
     */
    @Synchronized
    fun initServer(connectEndpointOverride: AdbConnectEndpoint? = null): Boolean {
        if (_running.value == true || (shizukuBackendEnabled && ShizukuAccess.isConnected())) return true
        if (tryingToPair) return false

        tryingToPair = true
        try {
        activeSerial = null
        prepareAdbHome()
        val autoShell = sharedPrefs.getBoolean(context.getString(R.string.auto_shell_key), true)

        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (autoShell && secureSettingsGranted) disableMobileDataAlwaysOn()

        var connectEndpoint: AdbConnectEndpoint? = connectEndpointOverride
        // The serial adb really connected with (127.0.0.1:<port> or the mDNS host:port).
        var connectedSerial: String? = null
        var connectionDeadline = 0L
        fun remainingTimeoutSeconds(limit: Long): Long {
            val remainingMillis = connectionDeadline - System.currentTimeMillis()
            if (remainingMillis <= 0L) {
                throw IllegalStateException("התקן ADB לא התחבר בתוך ${CONNECT_BUDGET_MS / 1_000L} שניות.")
            }
            return minOf(limit, ((remainingMillis + 999L) / 1_000L).coerceAtLeast(1L))
        }

        if (autoShell) {
            /* Only do wireless debugging steps on compatible versions */
            if (secureSettingsGranted) {
                // Never toggle Wireless Debugging automatically. Reconnect attempts must
                // keep the existing OS debugging session alive instead of invalidating it.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !isUSBDebuggingEnabled()) {
                    debug("Turning on USB debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.ADB_ENABLED,
                        1
                    )

                    Thread.sleep(5_000)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isWirelessDebuggingEnabled()) {
                throw IllegalStateException(
                    "Wireless Debugging כבוי. הפעל אותו באפשרויות למפתחים ונסה שוב."
                )
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !isUSBDebuggingEnabled()) {
                throw IllegalStateException(
                    "USB Debugging כבוי. הפעל אותו באפשרויות למפתחים ונסה שוב."
                )
            }

            // Every connection attempt performs its own fresh mDNS scan. No Activity,
            // ViewModel or singleton starts discovery in advance or stores an old port.
            if (connectEndpoint == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                debug("מאתר פורט TLS חדש של Wireless Debugging באמצעות mDNS…")
                connectEndpoint = LocalAdbServiceDiscovery(context).findConnectEndpoint(12_000L)
                    ?: throw IllegalStateException(
                        "לא נמצא שירות _adb-tls-connect במכשיר. ודא ש-Wireless Debugging פועל ושהמכשיר מחובר ל-Wi-Fi."
                    )
            }

            if (connectEndpoint != null) {
                debug("נמצא פורט חיבור חדש: ${connectEndpoint.serial} (${connectEndpoint.serviceName})")
            } else {
                debug("Android ישן: ממתין לחיבור USB/ADB.")
            }

            // Keep device connection and shell verification bounded separately from discovery.
            connectionDeadline = System.currentTimeMillis() + CONNECT_BUDGET_MS

            debug("Starting ADB server...")
            val startResult = captureProcessOutput(
                processForAdb(listOf("start-server"), mergeError = true),
                remainingTimeoutSeconds(8),
                "הפעלת שרת ADB חרגה מזמן ההמתנה."
            )
            if (startResult.first != 0) {
                debug("הפעלת שרת ADB נכשלה: ${startResult.second}")
                return false
            }

            if (connectEndpoint != null) {
                // The Wi-Fi address (found by mDNS a moment ago) goes first: on many devices the
                // Wireless Debugging port does not answer on loopback at all. Loopback is the fallback.
                val mdnsSerial = connectEndpoint.serial
                val loopbackSerial = "127.0.0.1:" + connectEndpoint.port
                val candidates = listOf(mdnsSerial, loopbackSerial).distinct()
                val failures = mutableListOf<String>()
                // Two rounds: right after pairing the first TLS connect can be slow or refused.
                // A timeout or error on one address is only a failure of that attempt; the next
                // address / round is always tried while the time budget lasts.
                attempts@ for (round in 1..2) {
                    for (serial in candidates) {
                        if (System.currentTimeMillis() >= connectionDeadline - 1_500L) break@attempts
                        val capSeconds = if (round == 1) 8L else 6L
                        debug("מתחבר אל $serial (ניסיון $round)…")
                        var connected = false
                        var detail = ""
                        try {
                            val connectResult = captureProcessOutput(
                                processForAdb(listOf("connect", serial), mergeError = true),
                                remainingTimeoutSeconds(capSeconds),
                                "אין תגובה מ־$serial תוך $capSeconds שניות."
                            )
                            val output = connectResult.second.trim()
                            detail = output.ifBlank { "exit=" + connectResult.first }
                            debug("ADB CONNECT $serial: $detail")
                            connected = connectResult.first == 0 &&
                                !output.contains("unable to connect", ignoreCase = true) &&
                                !output.contains("cannot connect", ignoreCase = true) &&
                                !output.contains("failed", ignoreCase = true)
                        } catch (error: Exception) {
                            detail = error.message ?: "שגיאה לא ידועה"
                            debug("ADB CONNECT $serial: $detail")
                        }
                        if (connected) {
                            connectedSerial = serial
                            break@attempts
                        }
                        failures.add("$serial (ניסיון $round): $detail")
                    }
                }
                if (connectedSerial == null) {
                    throw IllegalStateException("השיוך הצליח, אך חיבור ADB נכשל בכל כתובות השירות המקומי:\n" + failures.joinToString("\n"))
                }
            } else {
                captureProcessOutput(
                    adb(false, listOf("wait-for-device")),
                    remainingTimeoutSeconds(4),
                    "לא נמצא התקן ADB מאושר. הפעל Wireless Debugging או חבר USB."
                )
            }

            val expectedSerial = connectedSerial
            val deviceDeadline = connectionDeadline
            var deviceList = emptyList<String>()
            while (System.currentTimeMillis() < deviceDeadline) {
                val remaining = ((deviceDeadline - System.currentTimeMillis() + 999L) / 1_000L).coerceAtLeast(1L)
                deviceList = runCatching { getDevices(remaining.coerceAtMost(2L)) }.getOrDefault(emptyList())
                if (deviceList.isNotEmpty() &&
                    (expectedSerial == null || deviceList.any { it.equals(expectedSerial, ignoreCase = true) })
                ) break
                if (System.currentTimeMillis() < deviceDeadline) Thread.sleep(150L)
            }
            if (autoShell && deviceList.isEmpty()) {
                throw IllegalStateException("השיוך הצליח אך ADB לא הציג התקן מאושר. בדוק ש-Wireless Debugging עדיין פעיל.")
            }
            if (expectedSerial != null && deviceList.none { it.equals(expectedSerial, ignoreCase = true) }) {
                throw IllegalStateException(
                    "ADB לא אימת את נקודת החיבור ${expectedSerial}. התקנים שאושרו: " +
                        deviceList.joinToString(", ").ifBlank { "אין" }
                )
            }
        }

        val deviceList = getDevices()
        Log.d("DEVICES", "Devices: $deviceList")
        if (autoShell && deviceList.isEmpty()) {
            debug("לא נמצא התקן ADB מאושר. בדוק את חיבור Wireless Debugging ונסה שוב.")
            _running.postValue(false)
            return false
        }

        shellProcess = if (autoShell) {
            var argList = listOf("shell")
            val preferredSerial = connectedSerial
            val preferredDevice = deviceList.firstOrNull {
                preferredSerial != null && it.equals(preferredSerial, ignoreCase = true)
            }
            if (preferredDevice != null) {
                Log.i("DEVICES", "Selecting exact mDNS endpoint: $preferredDevice")
                argList = listOf("-s", preferredDevice, "shell")
            } else if (deviceList.size > 1) {
                Log.w("DEVICES", "Multiple devices detected...")
                val localDevices = deviceList.filter { it ->
                    it.contains("localhost")
                }

                /* Choose the first local device (hopefully the only). */
                if (localDevices.isNotEmpty()) {
                    val serialId = localDevices.first()
                    Log.w("DEVICES", "Choosing first local device: $serialId")
                    argList = listOf("-s", serialId, "shell")
                } else {
                    /*
                     * If no local devices to use, try to filter out
                     * any emulator devices and choose the first remaining result.
                     */

                    val nonEmulators = deviceList.filterNot { it ->
                        it.contains("emulator")
                    }

                    /* Choose the first non emulator device (hopefully the only). */
                    if (nonEmulators.isNotEmpty()) {
                        val serialId = nonEmulators.first()
                        Log.w("DEVICES", "Choosing first non-emulator device: $serialId")
                        argList = listOf("-s", serialId, "shell")
                    } else {
                        /* Otherwise, we're screwed, just choose the first device. */
                        val serialId = deviceList.first()
                        Log.w("DEVICES", "Choosing first unrecognized device: $serialId")
                        argList = listOf("-s", serialId, "shell")
                    }
                }
            }

            // Confirm the selected device can execute a real shell command before
            // publishing the connection as ready. "device" in adb devices alone is not enough.
            if (autoShell) {
                val selectedSerial = argList.getOrNull(1)?.takeIf { argList.firstOrNull() == "-s" }
                    ?: deviceList.first()
                // From now on every adb call (install, exec-out, shell ...) targets this device.
                activeSerial = selectedSerial
                val probe = captureProcessOutput(
                    processForAdb(listOf("-s", selectedSerial, "shell", "id"), mergeError = true),
                    remainingTimeoutSeconds(6),
                    "בדיקת shell מול ההתקן חרגה מזמן ההמתנה."
                )
                if (probe.first != 0 || !Regex("""uid=\d+""").containsMatchIn(probe.second)) {
                    throw IllegalStateException(
                        "ADB זיהה התקן, אך לא הצליח לפתוח shell. תשובת בדיקה: " +
                            probe.second.take(500).ifBlank { "ריקה" }
                    )
                }
                debug("אומת shell פעיל מול $selectedSerial")
            }
            adb(true, argList)
        } else {
            shell(true, listOf("sh", "-l"))
        }

        sendToShellProcess("alias adb=\"$adbPath\"")

        if (!secureSettingsGranted) {
            sendToShellProcess("pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS &> /dev/null")
        }

        if (autoShell)
            sendToShellProcess("echo 'Entered adb shell'")
        else
            sendToShellProcess("echo 'Entered non-adb shell'")

        val startupCommand =
            sharedPrefs.getString(context.getString(R.string.startup_command_key), "echo 'Success! ※\\(^o^)/※'") ?: ""
        if (startupCommand.isNotEmpty())
            sendToShellProcess(startupCommand)

        _running.postValue(true)
        ensureShellDeathMonitor()
        return true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            _running.postValue(false)
            debug("החיבור הופסק.")
            return false
        } catch (error: Exception) {
            _running.postValue(false)
            debug("שגיאת חיבור: ${error.message ?: "לא ידועה"}")
            return false
        } finally {
            tryingToPair = false
        }
    }

    private fun isWirelessDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    private fun isUSBDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private fun isMobileDataAlwaysOnEnabled() =
        Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1

    /**
     * Settings.Global.MOBILE_DATA_ALWAYS_ON creates a bug
     * with the DNS resolver.
     */
    fun disableMobileDataAlwaysOn() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            // Only turn it off if it's already on.
            if (isMobileDataAlwaysOnEnabled()) {
                debug("Disabling 'Mobile data always on'...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "mobile_data_always_on",
                    0
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * Cycles wireless debugging to get a new port to scan.
     *
     * For whatever reason, Wireless Debugging needs to be
     * cycled twice to broadcast a valid port.
     */
    /** Wireless Debugging is intentionally never cycled during normal connection or reconnect. */

    /** Starts exactly one monitor for the long-lived interactive ADB shell. */
    fun ensureShellDeathMonitor() {
        if (!deathMonitorStarted.compareAndSet(false, true)) return
        Thread {
            try {
                waitForDeathAndReset()
            } finally {
                deathMonitorStarted.set(false)
            }
        }.apply { isDaemon = true; name = "ADBConnectionMonitor"; start() }
    }

    /**
     * Wait restart the shell once it dies
     */
    fun waitForDeathAndReset() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                if (tryingToPair) {
                    Thread.sleep(150)
                    continue
                }

                val watchedProcess = shellProcess
                if (watchedProcess == null) {
                    Thread.sleep(500)
                    continue
                }
                watchedProcess.waitFor()
                if (shellProcess !== watchedProcess) continue

                shellProcess = null
                _running.postValue(false)
                _closed.postValue(true)
                debug("חיבור ADB נותק. מנסה שחזור אחד…")

                runCatching {
                    val killer = adb(false, listOf("kill-server"))
                    if (!killer.waitFor(3, TimeUnit.SECONDS)) killer.destroyForcibly()
                }
                Thread.sleep(1_000)

                val restored = runCatching { initServer() }.getOrDefault(false)
                if (restored) {
                    _closed.postValue(false)
                    debug("החיבור שוחזר.")
                    continue
                }

                debug("השחזור האוטומטי נכשל. לחץ על התחבר מחדש כדי לנסות שוב.")
                _closed.postValue(true)
                return
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (error: Exception) {
                _running.postValue(false)
                _closed.postValue(true)
                debug("ניטור החיבור נעצר: ${error.message ?: "לא ידועה"}. לחץ על התחבר מחדש.")
                return
            }
        }
    }

    /**
     * Ask the device to pair on Android 11+ devices
     */
    fun pair(port: String, pairingCode: String, host: String = "localhost"): Boolean {
        val numericPort = port.toIntOrNull()
        if (numericPort == null || numericPort !in 1..65535 || !pairingCode.matches(Regex("\\d{6}"))) return false

        tryingToPair = true
        var pairProcess: Process? = null
        try {
            prepareAdbHome()
            // Drain ADB output while it is running. Waiting before reading can fill the pipe
            // and make the pairing process appear to hang.
            val cleanHost = host.trim().removePrefix("[").removeSuffix("]").ifBlank { "localhost" }
            val endpoint = if (cleanHost.contains(":")) "[$cleanHost]:$numericPort" else "$cleanHost:$numericPort"
            val activeProcess = ProcessBuilder(adbPath, "pair", endpoint)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().put("HOME", context.filesDir.path)
                    environment().put("TMPDIR", context.cacheDir.path)
                }
                .start()
            pairProcess = activeProcess

            val captured = StringBuilder()
            val outputReader = Thread {
                runCatching {
                    activeProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(captured) { captured.append(line).append('\n') }
                            debug("ADB PAIR: $line")
                        }
                    }
                }
            }.apply { isDaemon = true; name = "ADBPairOutput"; start() }

            // Feed the code immediately. ADB reads stdin after its pairing prompt;
            // the pipe buffers this line, so there is no artificial multi-second delay.
            activeProcess.outputStream.bufferedWriter().use { writer ->
                writer.write(pairingCode)
                writer.newLine()
                writer.flush()
            }

            val finished = activeProcess.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                activeProcess.destroyForcibly()
                activeProcess.waitFor(2, TimeUnit.SECONDS)
                debug("ADB pairing timed out.")
                return false
            }
            outputReader.join(2_000)
            val outputText = synchronized(captured) { captured.toString() }
            val success = activeProcess.exitValue() == 0 &&
                outputText.contains("success", ignoreCase = true)
            if (!success) debug("ADB pairing failed: " + outputText.takeLast(1200))
            return success
        } catch (error: Exception) {
            debug("ADB pairing error: ${error.message ?: "unknown error"}")
            runCatching { pairProcess?.destroyForcibly() }
            return false
        } finally {
            // Match the proven LADB pairing lifecycle: stop the pair session's server,
            // then let initServer create a clean server for the TLS connection.
            runCatching {
                val stop = processForAdb(listOf("kill-server"), mergeError = true)
                captureProcessOutput(stop, 3, "סגירת שרת ADB אחרי שיוך חרגה מזמן ההמתנה.")
            }.onFailure { Log.w("ADB", "Could not reset ADB server after pairing", it) }
            tryingToPair = false
        }
    }

    /**
     * Install one APK file through the bundled ADB client.
     */
    fun installApk(file: File): String {
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("קובץ APK לא נמצא")
        requireAdbBinaryBackend()
        val process = processForAdb(listOf("install", "-r", file.absolutePath), mergeError = true)
        val (exitCode, capturedOutput) = captureProcessOutput(process, 120, "התקנת APK חרגה מזמן ההמתנה")
        val output = capturedOutput.trim()
        debug("APK INSTALL: " + file.name + "\n" + output)
        if (exitCode != 0 || !output.contains("Success", ignoreCase = true)) {
            throw IllegalStateException(output.ifBlank { "ADB install נכשל" })
        }
        return output
    }

    /**
     * Send a raw ADB command
     */
    private fun adb(redirect: Boolean, command: List<String>): Process {
        val commandList = command.toMutableList().also {
            it.add(0, adbPath)
        }
        return shell(redirect, commandList)
    }

    /**
     * Send a raw shell command
     */
    private fun shell(redirect: Boolean, command: List<String>): Process {
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (redirect) redirectErrorStream(true)
                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                    activeSerial?.let { put("ANDROID_SERIAL", it) }
                }
            }
        val process = processBuilder.start()
        if (redirect) {
            Thread {
                runCatching {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) appendOutput(buffer, count)
                        }
                    }
                }
            }.apply { isDaemon = true; name = "ADBOutputCapture"; start() }
        }
        return process
    }

    /**
     * Send commands directly to the shell process
     */
    fun sendToShellProcess(msg: String) {
        if (shizukuBackendEnabled) {
            Thread {
                runCatching { ShizukuAccess.executeShell(msg, 60) }
                    .onSuccess { result ->
                        debug("$ $msg")
                        if (result.output.isNotBlank()) debug(result.output)
                        debug("exit code: ${result.exitCode}")
                    }
                    .onFailure { error ->
                        debug("Shizuku command failed: ${error.message ?: "unknown error"}")
                    }
            }.apply { isDaemon = true; name = "ShizukuCommand"; start() }
            return
        }
        val process = shellProcess ?: return
        if (!process.isAlive) return
        runCatching {
            synchronized(process) {
                PrintStream(process.outputStream).apply {
                    println(msg)
                    flush()
                }
            }
        }.onFailure { Log.w("ADB", "Could not send command to the shell", it) }
    }

    /**
     * Write a debug message to the user
     */
    fun debug(msg: String) {
        Log.d("DEBUG", msg)
        val bytes = ("* $msg" + System.lineSeparator()).toByteArray(Charsets.UTF_8)
        appendOutput(bytes, bytes.size)
    }

    private fun appendOutput(bytes: ByteArray, count: Int) {
        synchronized(outputBufferFile) {
            runCatching {
                java.io.FileOutputStream(outputBufferFile, true).use { it.write(bytes, 0, count) }
                val maxBytes = getOutputBufferSize().coerceIn(32 * 1024, 4 * 1024 * 1024)
                val currentSize = outputBufferFile.length()
                if (currentSize > maxBytes) {
                    val tail = ByteArray(maxBytes)
                    java.io.RandomAccessFile(outputBufferFile, "r").use { input ->
                        input.seek(currentSize - maxBytes)
                        input.readFully(tail)
                    }
                    java.io.FileOutputStream(outputBufferFile, false).use { it.write(tail) }
                }
            }.onFailure { Log.w("ADB", "Could not append shell output", it) }
        }
    }

    data class CommandResult(val exitCode: Int, val output: String)

    /** Drains process output while waiting, preventing a full stdout pipe from deadlocking ADB. */
    private fun captureProcessOutput(
        process: Process,
        timeoutSeconds: Long,
        timeoutMessage: String
    ): Pair<Int, String> {
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = (2 * 1024 * 1024 - output.size()).coerceAtLeast(0)
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; name = "ADBProcessOutput"; start() }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2_000)
            throw IllegalStateException(timeoutMessage)
        }
        reader.join(5_000)
        if (reader.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("קריאת פלט ADB לא הסתיימה בזמן.")
        }
        val text = synchronized(output) { output.toString("UTF-8") }
        return process.exitValue() to text
    }

    private fun processForAdb(arguments: List<String>, mergeError: Boolean = false): Process {
        return ProcessBuilder(listOf(adbPath) + arguments)
            .directory(context.filesDir)
            .apply {
                if (mergeError) redirectErrorStream(true)
                environment().put("HOME", context.filesDir.path)
                environment().put("TMPDIR", context.cacheDir.path)
                activeSerial?.let { environment().put("ANDROID_SERIAL", it) }
            }
            .start()
    }

    /**
     * Binary transfers and APK installs go through the bundled adb client, which only exists in
     * Wireless-ADB mode. Fail with a clear message instead of an obscure adb error under Shizuku.
     */
    private fun requireAdbBinaryBackend() {
        if (shizukuBackendEnabled) {
            throw IllegalStateException(
                "הפעולה הזו (גיבוי, שחזור או התקנת APK) נתמכת רק דרך Wireless Debugging ולא דרך Shizuku. " +
                    "התחבר דרך Wireless Debugging ונסה שוב."
            )
        }
        if (_running.value != true) throw IllegalStateException("אין חיבור ADB פעיל")
    }

    private fun remoteStatusPath(): String =
        "/data/local/tmp/.adb-smart-${java.util.UUID.randomUUID().toString().replace("-", "")}.status"

    /**
     * exec-out / exec-in use the raw (non shell-v2) protocol, so the real exit code is lost and
     * stderr would be mixed into the binary stream. The command therefore runs in a subshell whose
     * stderr goes to a side file; the exit code goes to a second file. Both are read afterwards.
     */
    private fun wrapWithRemoteStatus(command: String, statusPath: String): String =
        "( $command ) 2>'${statusPath}.err'; __adb_status=\$?; printf '%s' \"\$__adb_status\" > '$statusPath'"

    /** Returns the remote exit code and whatever the command wrote to stderr. */
    private fun consumeRemoteStatus(statusPath: String): Pair<Int, String> {
        val result = executeShell(
            "if [ -f '$statusPath' ]; then cat '$statusPath'; else printf missing; fi; " +
                "printf '\\n---ERR---\\n'; head -c 2000 '${statusPath}.err' 2>/dev/null; " +
                "rm -f '$statusPath' '${statusPath}.err'",
            20
        )
        val marker = "---ERR---"
        val index = result.output.indexOf(marker)
        val head = if (index >= 0) result.output.substring(0, index) else result.output
        val errorText = if (index >= 0) result.output.substring(index + marker.length).trim() else ""
        val code = head.trim().toIntOrNull()
            ?: throw IllegalStateException("לא התקבל קוד ביצוע מהמכשיר המרוחק: ${result.output.take(300)}")
        return code to errorText
    }

    /** Executes one bounded command through the authorized local Wireless ADB connection. */
    fun hasPrivilegedShell(): Boolean =
        if (shizukuBackendEnabled) ShizukuAccess.isConnected() else _running.value == true

    /** Switch to the explicitly approved Shizuku UserService backend. */
    fun activateShizukuBackend() {
        if (!ShizukuAccess.isConnected()) {
            throw IllegalStateException("Shizuku אינו מחובר או שלא אושרה ההרשאה.")
        }
        shizukuBackendEnabled = true
        _closed.postValue(false)
        _running.postValue(true)
        debug("נפתח shell דרך Shizuku (UID ${ShizukuAccess.serverUid()}).")
    }

    fun deactivateShizukuBackend() {
        if (!shizukuBackendEnabled) return
        shizukuBackendEnabled = false
        _running.postValue(false)
        _closed.postValue(true)
        debug("חיבור Shizuku נותק.")
    }

    fun executeShell(command: String, timeoutSeconds: Long = 30): CommandResult {
        require(command.isNotBlank() && command.length <= 12_000) { "Invalid shell command" }
        if (shizukuBackendEnabled) {
            return ShizukuAccess.executeShell(command, timeoutSeconds)
        }
        if (_running.value != true) throw IllegalStateException("אין חיבור ADB פעיל")
        // The command is ONE argument: adb joins arguments with spaces without any escaping, so the
        // old `shell sh -c <command>` form broke every command that contains a space.
        val process = processForAdb(listOf("shell", command), mergeError = true)
        runCatching { process.outputStream.close() } // remote stdin gets EOF; nothing waits for input
        val (exitCode, output) = captureProcessOutput(process, timeoutSeconds, "פקודת ADB חרגה מזמן ההמתנה")
        return CommandResult(exitCode, output.trim())
    }

    /** Streams binary stdout from adb exec-out to a local file without converting it to text. */
    fun execOutToFile(command: String, destination: File, timeoutSeconds: Long = 300): CommandResult {
        require(command.isNotBlank() && command.length <= 12_000) { "Invalid shell command" }
        requireAdbBinaryBackend()
        destination.parentFile?.mkdirs()
        val statusPath = remoteStatusPath()
        val wrapped = wrapWithRemoteStatus(command, statusPath)
        val process = processForAdb(listOf("exec-out", wrapped))
        runCatching { process.outputStream.close() }
        val errorText = StringBuilder()
        val stdoutThread = Thread {
            runCatching {
                java.io.FileOutputStream(destination).use { out ->
                    process.inputStream.use { it.copyTo(out, 64 * 1024) }
                }
            }
        }.apply { isDaemon = true; start() }
        val stderrThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().use { errorText.append(it.readText()) }
            }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdoutThread.join(2_000)
            stderrThread.join(2_000)
            destination.delete()
            throw IllegalStateException("העברת קובץ מ־ADB חרגה מזמן ההמתנה")
        }
        stdoutThread.join(5_000)
        stderrThread.join(5_000)
        if (stdoutThread.isAlive || stderrThread.isAlive) {
            process.destroyForcibly()
            destination.delete()
            throw IllegalStateException("העברת נתוני ADB לא הסתיימה בזמן.")
        }
        if (process.exitValue() != 0) {
            destination.delete()
            throw IllegalStateException(errorText.toString().ifBlank { "העברת הנתונים דרך ADB נכשלה" })
        }
        val (remoteExit, remoteError) = try { consumeRemoteStatus(statusPath) } catch (e: Exception) {
            destination.delete()
            throw e
        }
        if (remoteExit != 0) {
            destination.delete()
            val reason = remoteError.ifBlank { errorText.toString().trim() }
                .ifBlank { "פקודת הקריאה במכשיר נכשלה (קוד $remoteExit)" }
            throw RemoteCommandException(remoteExit, reason)
        }
        if (!destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw IllegalStateException(
                remoteError.ifBlank { errorText.toString().trim() }.ifBlank { "הפקודה הסתיימה ללא נתונים" }
            )
        }
        return CommandResult(remoteExit, remoteError)
    }

    /** Streams a local binary archive to a remote command through adb exec-in. */
    fun execInFile(source: File, command: String, timeoutSeconds: Long = 300): CommandResult {
        require(source.isFile && source.canRead()) { "קובץ הגיבוי אינו קריא" }
        require(command.isNotBlank() && command.length <= 12_000) { "Invalid shell command" }
        requireAdbBinaryBackend()
        val statusPath = remoteStatusPath()
        val wrapped = wrapWithRemoteStatus(command, statusPath)
        val process = processForAdb(listOf("exec-in", wrapped))
        val errorText = StringBuilder()
        val reader = Thread {
            runCatching { process.errorStream.bufferedReader().use { errorText.append(it.readText()) } }
        }.apply { isDaemon = true; name = "ADBExecInErrors"; start() }
        val copyFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val writer = Thread {
            try {
                source.inputStream().use { input ->
                    process.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
                }
            } catch (error: Throwable) {
                copyFailure.set(error)
                runCatching { process.outputStream.close() }
            }
        }.apply { isDaemon = true; name = "ADBExecInWriter"; start() }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            writer.join(2_000)
            reader.join(2_000)
            throw IllegalStateException("שחזור קובץ דרך ADB חרג מזמן ההמתנה")
        }
        writer.join(5_000)
        reader.join(5_000)
        if (writer.isAlive || reader.isAlive) {
            process.destroyForcibly()
            throw IllegalStateException("העברת קובץ השחזור לא הסתיימה בזמן.")
        }
        copyFailure.get()?.let { throw IllegalStateException("לא ניתן להעביר את קובץ הגיבוי: ${it.message}", it) }
        if (process.exitValue() != 0) {
            throw IllegalStateException(errorText.toString().ifBlank { "העברת הנתונים למכשיר נכשלה" })
        }
        val (remoteExit, remoteError) = consumeRemoteStatus(statusPath)
        return CommandResult(remoteExit, remoteError.ifBlank { errorText.toString().trim() })
    }

    /** Installs a base APK together with all split APKs in one Package Manager session. */
    fun installApkSet(files: List<File>): String {
        require(files.isNotEmpty() && files.all { it.isFile && it.canRead() }) {
            "קובצי APK חסרים או אינם קריאים"
        }
        requireAdbBinaryBackend()
        val process = processForAdb(
            listOf("install-multiple", "-r") + files.map { it.absolutePath },
            mergeError = true
        )
        val (exitCode, capturedOutput) = captureProcessOutput(process, 180, "התקנת חבילת APK חרגה מזמן ההמתנה")
        val output = capturedOutput.trim()
        debug("APK SET INSTALL:\n$output")
        if (exitCode != 0 || !output.contains("Success", ignoreCase = true)) {
            throw IllegalStateException(output.ifBlank { "התקנת חבילת APK נכשלה" })
        }
        return output
    }

}