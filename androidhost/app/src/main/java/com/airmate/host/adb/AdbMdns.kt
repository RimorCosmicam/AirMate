package com.airmate.host.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds the two ports wireless debugging announces, so nobody has to read them off a dialog.
 *
 * Android picks both at random and changes them every time debugging is toggled, which is why an
 * app that asks the user to type a port is asking them to do it again tomorrow. Both are published
 * over mDNS on the device's own network: `_adb-tls-pairing._tcp` appears only while the pairing
 * dialog is open, `_adb-tls-connect._tcp` for as long as debugging is on.
 */
class AdbMdns(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _pairingPort = MutableStateFlow<Int?>(null)
    val pairingPort = _pairingPort.asStateFlow()

    private val _connectPort = MutableStateFlow<Int?>(null)
    val connectPort = _connectPort.asStateFlow()

    private val _host = MutableStateFlow(LOOPBACK)
    val host = _host.asStateFlow()

    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null

    fun start() {
        val manager = nsd ?: run {
            Log.w(TAG, "no NsdManager on this device; ports must be typed by hand")
            return
        }
        stop()
        pairingListener = listenerFor("pairing") { info ->
            _pairingPort.value = info.port
            info.host?.hostAddress?.let { _host.value = it }
        }
        connectListener = listenerFor("connect") { info ->
            _connectPort.value = info.port
            info.host?.hostAddress?.let { _host.value = it }
        }
        runCatching {
            manager.discoverServices(PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        }.onFailure { Log.e(TAG, "pairing discovery would not start", it) }
        runCatching {
            manager.discoverServices(CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
        }.onFailure { Log.e(TAG, "connect discovery would not start", it) }
    }

    fun stop() {
        pairingListener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        connectListener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        pairingListener = null
        connectListener = null
    }

    /** The pairing port is only meaningful while the dialog is open; forget it when it closes. */
    fun forgetPairingPort() {
        _pairingPort.value = null
    }

    private fun listenerFor(what: String, onResolved: (NsdServiceInfo) -> Unit) =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.e(TAG, "$what discovery failed to start: $error")
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                runCatching {
                    @Suppress("DEPRECATION")
                    nsd?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                            Log.w(TAG, "$what resolve failed: $error")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            Log.i(TAG, "$what on ${info.host?.hostAddress}:${info.port}")
                            onResolved(info)
                        }
                    })
                }
            }
        }

    companion object {
        private const val TAG = "AirMate.Host.Mdns"
        const val LOOPBACK = "127.0.0.1"
        private const val PAIRING = "_adb-tls-pairing._tcp"
        private const val CONNECT = "_adb-tls-connect._tcp"
    }
}
