package com.draco.ladb.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG_LOCAL_ADB_DISCOVERY = "LocalAdbDiscovery"

/** A single, fresh discovery result for the local Wireless Debugging TLS connect service. */
data class AdbConnectEndpoint(
    val host: String,
    val port: Int,
    val serviceName: String
) {
    val serial: String
        get() = "$host:$port"
}

/**
 * One-shot mDNS discovery modelled on Shizuku's AdbMdns implementation.
 *
 * Pairing port and code are deliberately not discovered here. The user supplies those
 * values directly from Android Settings. This class discovers only the separate TLS
 * connect port after pairing, without a process-wide singleton or cached endpoint.
 */
class LocalAdbServiceDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun findConnectEndpoint(timeoutMillis: Long = 12_000L): AdbConnectEndpoint? {
        val found = AtomicReference<AdbConnectEndpoint?>(null)
        val finished = CountDownLatch(1)
        val resolvingNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun isAddressOnThisDevice(address: InetAddress): Boolean = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            interfaces.asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .any { networkInterface ->
                    networkInterface.inetAddresses.asSequence().any { local ->
                        local.address.contentEquals(address.address)
                    }
                }
        }.getOrDefault(false)

        // Same test used by Shizuku: binding succeeds only if no local adbd listener owns this port.
        fun isLocalAdbdPort(port: Int): Boolean = try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress("127.0.0.1", port), 1)
                false
            }
        } catch (_: IOException) {
            true
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG_LOCAL_ADB_DISCOVERY, "Discovery started: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG_LOCAL_ADB_DISCOVERY, "Discovery start failed: $errorCode")
                finished.countDown()
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG_LOCAL_ADB_DISCOVERY, "Discovery stopped: $serviceType")
                finished.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG_LOCAL_ADB_DISCOVERY, "Discovery stop failed: $errorCode")
                finished.countDown()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.orEmpty().contains("_adb-tls-connect", ignoreCase = true)) return
                val name = serviceInfo.serviceName.orEmpty()
                if (!resolvingNames.add(name)) return

                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolvingNames.remove(name)
                            Log.d(TAG_LOCAL_ADB_DISCOVERY, "Resolve failed ($errorCode): $name")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            try {
                                val port = info.port
                                val host = info.host
                                if (port !in 1..65535 || host == null) return
                                if (!isAddressOnThisDevice(host)) {
                                    Log.d(TAG_LOCAL_ADB_DISCOVERY, "Ignoring non-local ADB service: \${host.hostAddress}")
                                    return
                                }
                                if (!isLocalAdbdPort(port)) {
                                    Log.d(TAG_LOCAL_ADB_DISCOVERY, "No local adbd listener on port $port")
                                    return
                                }

                                val endpoint = AdbConnectEndpoint("127.0.0.1", port, info.serviceName.orEmpty())
                                if (found.compareAndSet(null, endpoint)) {
                                    Log.i(TAG_LOCAL_ADB_DISCOVERY, "Found fresh local ADB endpoint: \${endpoint.serial}")
                                    finished.countDown()
                                }
                            } finally {
                                resolvingNames.remove(name)
                            }
                        }
                    })
                } catch (error: Exception) {
                    resolvingNames.remove(name)
                    Log.w(TAG_LOCAL_ADB_DISCOVERY, "Could not resolve $name", error)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }

        return try {
            nsdManager.discoverServices(
                "_adb-tls-connect._tcp.",
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
            finished.await(timeoutMillis.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            found.get()
        } catch (error: Exception) {
            Log.e(TAG_LOCAL_ADB_DISCOVERY, "Local ADB mDNS discovery failed", error)
            found.get()
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            resolvingNames.clear()
        }
    }
}
