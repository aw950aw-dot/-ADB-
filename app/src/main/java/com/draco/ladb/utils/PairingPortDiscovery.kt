package com.draco.ladb.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                val type = service.serviceType.orEmpty()
                if (!type.contains("_adb-tls-pairing", ignoreCase = true)) return
                if (!resolving.compareAndSet(false, true)) return
                try {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolving.set(false)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val port = serviceInfo.port
                            if (port in 1..65535) {
                                foundPort.set(port)
                                finished.countDown()
                            } else {
                                resolving.set(false)
                            }
                        }
                    })
                } catch (_: Exception) {
                    resolving.set(false)
                }
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
