package com.draco.ladb.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Finds the ephemeral Wireless Debugging pairing port advertised by Android. */
class PairingPortDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun findPairingPort(timeoutMillis: Long = 15_000L): Int? {
        val foundPort = AtomicInteger(-1)
        val finished = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()

        lateinit var resolveNext: () -> Unit
        resolveNext = {
            if (foundPort.get() <= 0 && resolving.compareAndSet(false, true)) {
                val next = pending.poll()
                if (next == null) {
                    resolving.set(false)
                    // Cover the narrow race where a service arrives as the queue becomes empty.
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
                                    if (port in 1..65535) {
                                        // Ignore other phones/tablets that may advertise pairing on the same Wi-Fi.
                                        val belongsToThisDevice = runCatching {
                                            NetworkInterface.getNetworkInterfaces().asSequence().any { network ->
                                                network.inetAddresses.asSequence().any { address ->
                                                    address.hostAddress.substringBefore('%') == serviceInfo.host.hostAddress.substringBefore('%')
                                                }
                                            }
                                        }.getOrDefault(false)

                                        // The pairing daemon owns this local port; a successful bind means it is not ours.
                                        val portIsOccupiedLocally = try {
                                            ServerSocket().use {
                                                it.bind(InetSocketAddress("127.0.0.1", port), 1)
                                                false
                                            }
                                        } catch (_: IOException) {
                                            true
                                        }

                                        if (belongsToThisDevice && portIsOccupiedLocally) {
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
                // Resolve serially: Android limits concurrent NSD resolve requests.
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
            finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
            foundPort.get().takeIf { it in 1..65535 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }
}
