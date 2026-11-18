package com.draco.ladb.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PairingEndpoint(val host: String, val port: Int)

/** Finds the current device's Wireless Debugging pairing endpoint over local NSD. */
class PairingPortDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun findPairingEndpoint(timeoutMillis: Long = 25_000L): PairingEndpoint? {
        val foundEndpoint = AtomicReference<PairingEndpoint?>(null)
        val finished = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()

        fun isLocalAddress(address: InetAddress): Boolean = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            interfaces.asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .any { networkInterface ->
                    networkInterface.inetAddresses.asSequence().any { local ->
                        local.address.contentEquals(address.address)
                    }
                }
        }.getOrDefault(false)

        // Match Shizuku's AdbMdns: an occupied loopback port indicates local adbd.
        fun isPortListeningLocally(port: Int): Boolean = try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", port), 1)
                false
            }
        } catch (_: IOException) {
            true
        }

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            if (foundEndpoint.get() == null && resolving.compareAndSet(false, true)) {
                val next = pending.poll()
                if (next == null) {
                    resolving.set(false)
                    if (pending.isNotEmpty()) resolveNext()
                } else {
                    try {
                        manager.resolveService(next, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                                resolveNext()
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                try {
                                    val port = serviceInfo.port
                                    val host = serviceInfo.host
                                    if (port in 1..65535 && host != null) {
                                        val isLocal = host.isLoopbackAddress || isLocalAddress(host)
                                        val localListener = isLocal && isPortListeningLocally(port)

                                        if (localListener) {
                                            foundEndpoint.compareAndSet(
                                                null,
                                                // Pair the embedded ADB client to Android's local adbd listener.
                                                PairingEndpoint("127.0.0.1", port)
                                            )
                                            finished.countDown()
                                        }
                                    }
                                } finally {
                                    resolving.set(false)
                                    if (foundEndpoint.get() == null) resolveNext()
                                }
                            }
                        })
                    } catch (_: Exception) {
                        resolving.set(false)
                        resolveNext()
                    }
                }
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.orEmpty().contains("_adb-tls-pairing", ignoreCase = true)) return
                pending.add(service)
                resolveNext()
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                if (foundEndpoint.get() == null) finished.countDown()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finished.countDown()
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                finished.countDown()
                runCatching { manager.stopServiceDiscovery(this) }
            }
        }

        return try {
            manager.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            finished.await(timeoutMillis.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            foundEndpoint.get()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    /** Kept for callers that only need the port. */
    fun findPairingPort(timeoutMillis: Long = 25_000L): Int? =
        findPairingEndpoint(timeoutMillis)?.port
}
