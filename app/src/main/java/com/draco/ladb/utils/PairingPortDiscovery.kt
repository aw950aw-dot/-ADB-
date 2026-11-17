package com.draco.ladb.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Finds the Wireless Debugging pairing port advertised by this device over local NSD. */
class PairingPortDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun findPairingPort(timeoutMillis: Long = 25_000L): Int? {
        val foundPort = AtomicInteger(-1)
        val finished = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()

        fun localAddresses(): List<InetAddress> = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
            interfaces.asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { it.inetAddresses.asSequence() }
                .toList()
        }.getOrDefault(emptyList())

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            if (foundPort.get() <= 0 && resolving.compareAndSet(false, true)) {
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
                                        val local = localAddresses()
                                        val belongsToThisDevice =
                                            host.isLoopbackAddress ||
                                                local.any { it.address.contentEquals(host.address) }

                                        // Do not select a pairing server advertised by another
                                        // phone on the same Wi-Fi. Verify the resolved endpoint
                                        // is one of this device's own addresses and is listening.
                                        val reachable = if (belongsToThisDevice) {
                                            runCatching {
                                                Socket().use { socket ->
                                                    socket.connect(InetSocketAddress(host, port), 900)
                                                }
                                            }.isSuccess
                                        } else {
                                            false
                                        }

                                        if (belongsToThisDevice && reachable) {
                                            foundPort.set(port)
                                            finished.countDown()
                                        }
                                    }
                                } finally {
                                    resolving.set(false)
                                    if (foundPort.get() <= 0) resolveNext()
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
                if (foundPort.get() <= 0) finished.countDown()
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
            foundPort.get().takeIf { it in 1..65535 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }
}
