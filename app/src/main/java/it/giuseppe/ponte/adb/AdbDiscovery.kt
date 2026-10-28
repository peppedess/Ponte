package it.giuseppe.ponte.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import kotlin.coroutines.resume

/** Un servizio ADB trovato in rete (Debug wireless di un altro dispositivo). */
data class AdbService(
    val name: String,
    val host: String,
    val port: Int,
) {
    /** "adb-R5CX12345AB-x1Y2z3" → "R5CX12345AB" */
    val serial: String
        get() = name.removePrefix("adb-").substringBeforeLast("-")
}

/**
 * Cerca con mDNS i servizi del Debug wireless:
 *  - `_adb-tls-pairing._tcp` compare quando sul Galaxy è aperta la schermata "Associa con codice";
 *  - `_adb-tls-connect._tcp` è sempre attivo quando il Debug wireless è acceso.
 * Esclude i servizi annunciati da questo stesso dispositivo.
 */
class AdbDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveLock = Mutex()

    fun services(type: String): Flow<List<AdbService>> = callbackFlow {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("ponte-mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val found = linkedMapOf<String, AdbService>()
        val local = localAddresses()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                launchResolve(info) { resolved ->
                    if (resolved == null) return@launchResolve
                    @Suppress("DEPRECATION")
                    val address = resolved.host ?: return@launchResolve
                    val host = address.hostAddress ?: return@launchResolve
                    if (host in local) return@launchResolve
                    val service = AdbService(resolved.serviceName, host.substringBefore('%'), resolved.port)
                    synchronized(found) {
                        found[resolved.serviceName] = service
                        trySend(found.values.toList())
                    }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(info.serviceName) != null) trySend(found.values.toList())
                }
            }
        }

        trySend(emptyList())
        nsd.discoverServices("_$type._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
    }

    /** Aspetta il primo servizio di connessione che soddisfa [match], oppure null allo scadere. */
    suspend fun findConnectService(timeoutMs: Long, match: (AdbService) -> Boolean): AdbService? =
        withTimeoutOrNull(timeoutMs) {
            services(TYPE_CONNECT).first { list -> list.any(match) }.first(match)
        }

    private fun launchResolve(info: NsdServiceInfo, onResult: (NsdServiceInfo?) -> Unit) {
        Thread {
            kotlinx.coroutines.runBlocking {
                // NsdManager risolve un servizio alla volta sulle versioni meno recenti
                resolveLock.withLock { onResult(resolve(info)) }
            }
        }.start()
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            }
        }

    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
    }.getOrDefault(emptySet())

    companion object {
        const val TYPE_PAIRING = "adb-tls-pairing"
        const val TYPE_CONNECT = "adb-tls-connect"
    }
}
