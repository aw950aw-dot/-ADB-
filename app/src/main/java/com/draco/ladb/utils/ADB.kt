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
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ADB(private val context: Context) {
    companion object {
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
        const val OUTPUT_BUFFER_DELAY_MS = 100L

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ADB? = null
        fun getInstance(context: Context): ADB = instance ?: synchronized(this) {
            instance ?: ADB(context).also { instance = it }
        }
    }

    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"
    private val scriptPath = "${context.getExternalFilesDir(null)}/script.sh"

    /**
     * Is the shell ready to handle commands?
     */
    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    private var tryingToPair = false

    /**
     * Is the shell closed for any reason?
     */
    private val _closed = MutableLiveData(false)
    val closed: LiveData<Boolean> = _closed

    /**
     * Where shell output is stored
     */
    val outputBufferFile: File = File.createTempFile("buffer", ".txt").also {
        it.deleteOnExit()
    }

    /**
     * Single shell instance where we can pipe commands to
     */
    private var shellProcess: Process? = null

    /**
     * Returns the user buffer size if valid, else the default
     */
    fun getOutputBufferSize(): Int {
        val userValue = sharedPrefs.getString(context.getString(R.string.buffer_size_key), "524288")!!
        return try {
            Integer.parseInt(userValue)
        } catch (_: NumberFormatException) {
            512 * 1024
        }
    }

    /**
     * Get a list of connected devices.
     */
    fun getDevices(): List<String> {
        val devicesProcess = adb(false, listOf("devices"))
        devicesProcess.waitFor()

        /* Get result of the command. */
        val linesRaw = BufferedReader(devicesProcess.inputStream.reader()).readLines()

        /* Remove "List of devices attached" line if it exists (it should). */
        val deviceLines = linesRaw.filterNot { it ->
            it.contains("List of devices attached")
        }

        /* Just get first part with device name/IP and port. */
        var deviceNames = deviceLines.map { it ->
            it.split("\t").first()
        }

        /* Remove any empty lines. */
        deviceNames = deviceNames.filterNot { it ->
            it.isEmpty()
        }

        for (name in deviceNames) {
            Log.d("LINES", "<<<$name>>>")
        }

        return deviceNames
    }

    /**
     * Start the ADB server
     */
    fun initServer(): Boolean {
        if (_running.value == true || tryingToPair)
            return true

        tryingToPair = true

        val autoShell = sharedPrefs.getBoolean(context.getString(R.string.auto_shell_key), true)

        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (autoShell) {
            /* Only do wireless debugging steps on compatible versions */
            if (secureSettingsGranted) {
                disableMobileDataAlwaysOn()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cycleWirelessDebugging()
                } else if (!isUSBDebuggingEnabled()) {
                    debug("Turning on USB debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.ADB_ENABLED,
                        1
                    )

                    Thread.sleep(5_000)
                }
            }

            /* Check again... */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!isWirelessDebuggingEnabled()) {
                    debug("Wireless debugging is not enabled!")
                    debug("Settings -> Developer options -> Wireless debugging")
                    debug("Waiting for wireless debugging...")

                    while (!isWirelessDebuggingEnabled()) {
                        Thread.sleep(1_000)
                    }
                }
            } else {
                if (!isUSBDebuggingEnabled()) {
                    debug("USB debugging is not enabled!")
                    debug("Settings -> Developer options -> USB debugging")
                    debug("Waiting for USB debugging...")

                    while (!isUSBDebuggingEnabled()) {
                        Thread.sleep(1_000)
                    }
                }
            }

            val nowTime = System.currentTimeMillis()
            val maxTimeoutTime = nowTime + 10.seconds.inWholeMilliseconds
            val minDnsScanTime = (DnsDiscover.aliveTime ?: nowTime) + 3.seconds.inWholeMilliseconds
            while (true) {
                val nowTime = System.currentTimeMillis()
                val pendingResolves = DnsDiscover.pendingResolves.get()

                // Wait for pending DNS resolves to finish and the minimum scan time to elapse...
                if (nowTime >= minDnsScanTime && !pendingResolves) {
                    debug("DNS resolver done...")
                    break
                }

                // Or if 10 seconds pass...
                if (nowTime >= maxTimeoutTime) {
                    debug("DNS resolver took too long! Skipping...")
                    break
                }

                debug("Awaiting DNS resolver...")

                Thread.sleep(1_000)
            }

            val adbPort = DnsDiscover.adbPort
            if (adbPort != null)
                debug("Best ADB port discovered: $adbPort")
            else
                debug("No ADB port discovered, fallback...")

            debug("Starting ADB server...")
            adb(false, listOf("start-server")).waitFor(1, TimeUnit.MINUTES)

            val waitProcess = if (adbPort != null)
                adb(false, listOf("connect", "localhost:$adbPort")).waitFor(1, TimeUnit.MINUTES)
            else
                adb(false, listOf("wait-for-device")).waitFor(1, TimeUnit.MINUTES)

            if (!waitProcess) {
                debug("Your device didn't connect to LADB")
                debug("If a reboot doesn't work, please contact support")

                if (isMobileDataAlwaysOnEnabled()) {
                    debug("Please disable 'Mobile data always on' in Developer Settings!")
                    Thread.sleep(5_000)
                }

                tryingToPair = false
                return false
            }
        }

        val deviceList = getDevices()
        Log.d("DEVICES", "Devices: $deviceList")

        shellProcess = if (autoShell) {
            var argList = listOf("shell")

            /* Uh oh, multiple possible devices... */
            if (deviceList.size > 1) {
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
            sharedPrefs.getString(context.getString(R.string.startup_command_key), "echo 'Success! ※\\(^o^)/※'")!!
        if (startupCommand.isNotEmpty())
            sendToShellProcess(startupCommand)

        _running.postValue(true)
        tryingToPair = false

        return true
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
    fun cycleWirelessDebugging() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                debug("Cycling wireless debugging, please wait...")
                // Only turn it off if it's already on.
                if (isWirelessDebuggingEnabled()) {
                    debug("Turning off wireless debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        0
                    )
                    Thread.sleep(3_000)
                }

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)

                debug("Turning off wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    0
                )
                Thread.sleep(3_000)

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * Wait restart the shell once it dies
     */
    fun waitForDeathAndReset() {
        while (true) {
            /* Do not falsely claim the shell is dead if we haven't even initialized it yet */
            if (tryingToPair) continue

            shellProcess?.waitFor()
            _running.postValue(false)
            debug("Shell is dead, resetting...")
            adb(false, listOf("kill-server")).waitFor()

            Thread.sleep(3_000)
            initServer()
        }
    }

    /**
     * Ask the device to pair on Android 11+ devices
     */
    fun pair(port: String, pairingCode: String): Boolean {
        val pairShell = adb(false, listOf("pair", "localhost:$port"))

        /* Sleep to allow shell to catch up */
        Thread.sleep(5000)

        /* Pipe pairing code */
        PrintStream(pairShell.outputStream).apply {
            println(pairingCode)
            flush()
        }

        /* Continue once finished pairing (or 10s elapses) */
        pairShell.waitFor(10, TimeUnit.SECONDS)
        pairShell.destroyForcibly().waitFor()

        val killShell = adb(false, listOf("kill-server"))
        killShell.waitFor(3, TimeUnit.SECONDS)
        killShell.destroyForcibly()

        return pairShell.exitValue() == 0
    }

    /**
     * Install one APK file through the bundled ADB client.
     */
    fun installApk(file: File): String {
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("קובץ APK לא נמצא")
        if (_running.value != true) throw IllegalStateException("אין חיבור ADB פעיל")
        val process = ProcessBuilder(adbPath, "install", "-r", file.absolutePath)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment().put("HOME", context.filesDir.path)
                environment().put("TMPDIR", context.cacheDir.path)
            }
            .start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("התקנת APK חרגה מזמן ההמתנה")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        debug("APK INSTALL: " + file.name + "\n" + output)
        if (process.exitValue() != 0 || !output.contains("Success", ignoreCase = true)) {
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
                if (redirect) {
                    redirectErrorStream(true)
                    redirectOutput(outputBufferFile)
                }

                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                }
            }

        return processBuilder.start()!!
    }

    /**
     * Send commands directly to the shell process
     */
    fun sendToShellProcess(msg: String) {
        if (shellProcess == null || shellProcess?.outputStream == null)
            return
        PrintStream(shellProcess!!.outputStream!!).apply {
            println(msg)
            flush()
        }
    }

    /**
     * Write a debug message to the user
     */
    fun debug(msg: String) {
        synchronized(outputBufferFile) {
            Log.d("DEBUG", msg)
            if (outputBufferFile.exists())
                outputBufferFile.appendText("* $msg" + System.lineSeparator())
        }
    }

    data class CommandResult(val exitCode: Int, val output: String)

    private fun processForAdb(arguments: List<String>): Process {
        return ProcessBuilder(listOf(adbPath) + arguments)
            .directory(context.filesDir)
            .apply {
                environment().put("HOME", context.filesDir.path)
                environment().put("TMPDIR", context.cacheDir.path)
            }
            .start()
    }

    /** Executes one bounded command through the authorized local Wireless ADB connection. */
    fun executeShell(command: String, timeoutSeconds: Long = 30): CommandResult {
        require(command.isNotBlank() && command.length <= 12_000) { "Invalid shell command" }
        if (_running.value != true) throw IllegalStateException("אין חיבור ADB פעיל")
        val process = processForAdb(listOf("shell", "sh", "-c", command))
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching { process.inputStream.use { it.copyTo(output) } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2_000)
            throw IllegalStateException("פקודת ADB חרגה מזמן ההמתנה")
        }
        reader.join(5_000)
        return CommandResult(process.exitValue(), output.toString("UTF-8").trim())
    }

    /** Streams binary stdout from adb exec-out to a local file without converting it to text. */
    fun execOutToFile(command: String, destination: File, timeoutSeconds: Long = 300): CommandResult {
        require(command.isNotBlank() && command.length <= 12_000) { "Invalid shell command" }
        if (_running.value != true) throw IllegalStateException("אין חיבור ADB פעיל")
        destination.parentFile?.mkdirs()
        val process = processForAdb(listOf("exec-out", "sh", "-c", command))
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
        val result = CommandResult(process.exitValue(), errorText.toString().trim())
        if (result.exitCode != 0 || !destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw IllegalStateException(result.output.ifBlank { "לא ניתן לקרוא את הנתונים דרך ADB" })
        }
        return result
    }

    /** Streams a local binary archive to a remote command through adb exec-in. */
    fun execInFile(source: File, command: String, timeoutSeconds: Long = 300): CommandResult {
        require(source.isFile && source.canRead()) { "קובץ הגיבוי אינו קריא" }
        require(command.isNotBlank() && command.length <= 12_000) { "Invalid shell command" }
        if (_running.value != true) throw IllegalStateException("אין חיבור ADB פעיל")
        val process = processForAdb(listOf("exec-in", "sh", "-c", command))
        val outputText = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { outputText.append(it.readText()) } }
        }.apply { isDaemon = true; start() }

        source.inputStream().use { input ->
            process.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
        }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2_000)
            throw IllegalStateException("שחזור קובץ דרך ADB חרג מזמן ההמתנה")
        }
        reader.join(5_000)
        return CommandResult(process.exitValue(), outputText.toString().trim())
    }

    /** Installs a base APK together with all split APKs in one Package Manager session. */
    fun installApkSet(files: List<File>): String {
        require(files.isNotEmpty() && files.all { it.isFile && it.canRead() }) {
            "קובצי APK חסרים או אינם קריאים"
        }
        if (_running.value != true) throw IllegalStateException("אין חיבור ADB פעיל")
        val args = mutableListOf(adbPath, "install-multiple", "-r")
        args.addAll(files.map { it.absolutePath })
        val process = ProcessBuilder(args)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment().put("HOME", context.filesDir.path)
                environment().put("TMPDIR", context.cacheDir.path)
            }
            .start()
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("התקנת חבילת APK חרגה מזמן ההמתנה")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        debug("APK SET INSTALL:\n$output")
        if (process.exitValue() != 0 || !output.contains("Success", ignoreCase = true)) {
            throw IllegalStateException(output.ifBlank { "התקנת חבילת APK נכשלה" })
        }
        return output
    }

}