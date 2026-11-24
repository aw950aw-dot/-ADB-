package com.aw950aw.smartbackup.wireless

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class Endpoint(val host: String, val port: Int)

/** Finds the Wireless Debugging services the device advertises on Wi-Fi (the same way Shizuku does). */
class MdnsDiscovery(private val context: Context) {

    companion object {
        const val PAIRING_TYPE = "_adb-tls-pairing._tcp."
        const val CONNECT_TYPE = "_adb-tls-connect._tcp."

        /** The device's own Wi-Fi IPv4 address, the address Android shows in the pairing dialog. */
        @Suppress("DEPRECATION")
        fun wifiIpv4(context: Context): String? {
            return try {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                for (network in manager.allNetworks) {
                    val capabilities = manager.getNetworkCapabilities(network) ?: continue
                    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val addresses = manager.getLinkProperties(network)?.linkAddresses ?: continue
                    for (link in addresses) {
                        val address = link.address
                        if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Waits up to [timeoutMs] for the first service of [type]. Returns null on timeout. */
    fun find(type: String, timeoutMs: Long, log: (String) -> Unit): Endpoint? {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val done = CountDownLatch(1)
        val lock = Any()
        var result: Endpoint? = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                log("חיפוש mDNS לא התחיל (קוד $errorCode).")
                done.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                // Resolving one service at a time; Android rejects parallel resolves.
                thread(name = "mdns-resolve", isDaemon = true) {
                    for (attempt in 1..6) {
                        if (done.count == 0L) return@thread
                        val resolved = resolveOnce(manager, serviceInfo)
                        if (resolved != null) {
                            synchronized(lock) {
                                if (result == null) result = resolved
                            }
                            done.countDown()
                            return@thread
                        }
                        try {
                            Thread.sleep(400)
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                    }
                }
            }
        }

        try {
            manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            log("חיפוש mDNS נכשל: ${error.message}")
            return null
        }
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        try {
            manager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
        return synchronized(lock) { result }
    }

    @Suppress("DEPRECATION")
    private fun resolveOnce(manager: NsdManager, info: NsdServiceInfo): Endpoint? {
        val latch = CountDownLatch(1)
        var endpoint: Endpoint? = null
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    latch.countDown()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    val address = serviceInfo?.host
                    val port = serviceInfo?.port ?: 0
                    if (address != null && port > 0) {
                        // An IPv6 link-local address is useless for a plain connect; use the Wi-Fi IPv4 instead.
                        val host = if (address is Inet4Address) address.hostAddress else wifiIpv4(context)
                        if (host != null) endpoint = Endpoint(host, port)
                    }
                    latch.countDown()
                }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return endpoint
    }
}
