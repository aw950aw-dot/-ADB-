package com.draco.ladb.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DNS"

class DnsDiscover private constructor(
    private val context: Context,
    private val nsdManager: NsdManager
) {
    @Volatile private var started = false
    private var bestExpirationTime: Long? = null
    private var bestServiceName: String? = null
    private val pendingNames = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile private var instance: DnsDiscover? = null
        @Volatile var adbPort: Int? = null
        val pendingResolves = AtomicBoolean(false)
        @Volatile var aliveTime: Long? = null

        fun getInstance(context: Context, nsdManager: NsdManager): DnsDiscover =
            instance ?: synchronized(this) {
                instance ?: DnsDiscover(context.applicationContext, nsdManager).also { instance = it }
            }
    }

    @Synchronized
    fun scanAdbPorts() {
        if (started) return
        started = true
        aliveTime = System.currentTimeMillis()
        adbPort = null
        bestExpirationTime = null
        bestServiceName = null
        pendingNames.clear()
        pendingResolves.set(false)
        try {
            nsdManager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (error: Exception) {
            started = false
            Log.e(TAG, "Could not start ADB service discovery", error)
        }
    }

    fun getLocalIpAddress(): String? = getLocalIpAddresses().firstOrNull()

    private fun getLocalIpAddresses(): Set<String> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        if (capabilities != null &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return emptySet()

        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptySet()
            val result = mutableSetOf<String>()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        result.add(address.hostAddress.substringBefore('%'))
                    }
                }
            }
            result
        }.getOrDefault(emptySet())
    }

    private fun updateIfNewest(info: NsdServiceInfo) {
        val port = info.port
        if (port !in 1..65535) return
        val expiry = parseExpirationTime(info.toString())
        val name = info.serviceName.orEmpty()
        val shouldUpdate = adbPort == null ||
            (expiry != null && (bestExpirationTime == null || expiry > bestExpirationTime!!)) ||
            (expiry == null && bestExpirationTime == null &&
                serviceNameRank(name) > serviceNameRank(bestServiceName.orEmpty()))
        if (shouldUpdate) {
            adbPort = port
            bestExpirationTime = expiry
            bestServiceName = name
            Log.d(TAG, "Selected local ADB port $port ($name)")
        }
    }

    private fun serviceNameRank(value: String): Int =
        Regex("\\((\\d+)\\)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun parseExpirationTime(raw: String): Long? {
        val value = Regex("""expirationTime: (\S+)""").find(raw)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)?.time
        }.getOrNull()
    }

    private fun finishResolve(name: String) {
        if (name.isNotBlank()) pendingNames.remove(name)
        pendingResolves.set(pendingNames.isNotEmpty())
    }

    private fun resolveService(service: NsdServiceInfo, retry: Int = 0) {
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed ($errorCode) for " + serviceInfo.serviceName)
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && retry < 2) {
                        mainHandler.postDelayed({ resolveService(service, retry + 1) }, 300L * (retry + 1))
                    } else {
                        finishResolve(service.serviceName.orEmpty())
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    try {
                        val port = serviceInfo.port
                        if (port !in 1..65535) return
                        val host = serviceInfo.host?.hostAddress?.substringBefore('%')
                        val localAddresses = getLocalIpAddresses()
                        if (host != null && host != "127.0.0.1" && host != "::1" &&
                            localAddresses.isNotEmpty() && host !in localAddresses) {
                            Log.d(TAG, "Ignoring ADB service not hosted by this device: $host")
                            return
                        }
                        updateIfNewest(serviceInfo)
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not process resolved ADB service", error)
                    } finally {
                        finishResolve(serviceInfo.serviceName.orEmpty())
                    }
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "Could not resolve ADB service", error)
            finishResolve(service.serviceName.orEmpty())
        }
    }

    val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "ADB service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            val name = service.serviceName.orEmpty()
            if (name.isBlank() || !pendingNames.add(name)) return
            pendingResolves.set(true)
            resolveService(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            finishResolve(service.serviceName.orEmpty())
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "ADB service discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "ADB discovery failed to start: $errorCode")
            pendingNames.clear()
            pendingResolves.set(false)
            synchronized(this@DnsDiscover) { started = false }
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "ADB discovery failed to stop: $errorCode")
        }
    }
}
