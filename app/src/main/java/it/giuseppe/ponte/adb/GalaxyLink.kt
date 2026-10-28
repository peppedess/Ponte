package it.giuseppe.ponte.adb

import android.content.Context
import io.github.muntashirakon.adb.AdbPairingRequiredException
import it.giuseppe.ponte.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Associazione e connessione al telefono selezionato tramite Debug wireless. */
object GalaxyLink {

    data class DeviceInfo(val name: String, val details: String, val oneUi: Int, val brand: String) {
        val isSamsung: Boolean get() = brand.equals("samsung", ignoreCase = true)
    }

    /** Id del telefono a cui è collegata adesso la connessione ADB (una sola alla volta). */
    @Volatile
    private var connectedId: String? = null

    /** Associa Ponte a un telefono con il codice a 6 cifre, lo salva e verifica subito la connessione. */
    suspend fun pair(context: Context, host: String, port: Int, code: String, serial: String) =
        withContext(Dispatchers.IO) {
            val adb = PonteAdb.get(context)
            try {
                adb.pair(host, port, code.trim())
            } catch (e: Exception) {
                throw LinkException("Associazione non riuscita: controlla il codice e riprova.", e)
            }
            Prefs.get(context).addPairedDevice(serial, host)
            connect(context)
        }

    /**
     * Si collega al telefono selezionato: prima prova l'ultimo indirizzo noto (immediato), poi cerca
     * in rete la porta attuale del Debug wireless (cambia ogni volta che viene riattivato).
     */
    suspend fun connect(context: Context): DeviceInfo = withContext(Dispatchers.IO) {
        val adb = PonteAdb.get(context)
        val prefs = Prefs.get(context)
        val device = prefs.current ?: throw LinkException("Nessun telefono associato.")

        if (adb.isConnected && connectedId == device.id && isAlive(adb)) {
            return@withContext readInfo(adb, prefs)
        }
        adb.disconnectQuietly()
        connectedId = null

        var lastError: Throwable? = null
        // Prima verifica veloce che la porta risponda: altrimenti il timeout TCP di sistema
        // (anche un minuto) bloccherebbe la schermata su "Connessione al telefono…"
        if (device.host.isNotEmpty() && device.port > 0 && isReachable(device.host, device.port)) {
            try {
                if (adb.connect(device.host, device.port)) {
                    connectedId = device.id
                    return@withContext readInfo(adb, prefs)
                }
            } catch (e: AdbPairingRequiredException) {
                throw LinkException("Il telefono non riconosce più Ponte: associalo di nuovo.", e)
            } catch (e: Exception) {
                lastError = e
                adb.disconnectQuietly()
            }
        }

        val service = AdbDiscovery(context).findConnectService(8_000) { s ->
            if (device.serial.isNotEmpty()) s.serial == device.serial || s.host == device.host else s.host == device.host
        } ?: throw LinkException(
            "${device.name.ifEmpty { "Telefono" }} non trovato. Attiva Opzioni sviluppatore → Debug wireless " +
                "e verifica che i due dispositivi siano sulla stessa rete Wi-Fi.",
            lastError,
        )

        if (!isReachable(service.host, service.port, 3_000)) {
            throw LinkException("Il telefono è stato trovato ma non risponde: controlla che sia sbloccato e sulla stessa rete Wi-Fi.")
        }
        try {
            if (!adb.connect(service.host, service.port) && !adb.isConnected) {
                throw LinkException("Il telefono ha rifiutato la connessione.")
            }
        } catch (e: AdbPairingRequiredException) {
            throw LinkException("Il telefono non riconosce più Ponte: associalo di nuovo.", e)
        } catch (e: LinkException) {
            throw e
        } catch (e: Exception) {
            adb.disconnectQuietly()
            throw LinkException("Connessione al telefono non riuscita (${e.message ?: e.javaClass.simpleName}).", e)
        }
        connectedId = device.id
        prefs.updateCurrent { it.copy(host = service.host, port = service.port) }
        readInfo(adb, prefs)
    }

    /** La connessione esistente risponde ancora? Con un timeout: se il Wi-Fi è caduto, aprire un flusso resta appeso. */
    private fun isAlive(adb: PonteAdb): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<Boolean> { adb.shell("echo ok") == "ok" }.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun isReachable(host: String, port: Int, timeoutMs: Int = 1_500): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)

    private fun readInfo(adb: PonteAdb, prefs: Prefs): DeviceInfo {
        val props = adb.shell(
            "echo \"$(getprop ro.product.marketname)|$(getprop ro.product.model)|" +
                "$(getprop ro.build.version.release)|$(getprop ro.build.version.oneui)|" +
                "$(getprop ro.product.manufacturer)|$(getprop ro.serialno)\"",
        ).split('|')
        val market = props.getOrNull(0).orEmpty().trim()
        val model = props.getOrNull(1).orEmpty().trim()
        val android = props.getOrNull(2).orEmpty().trim()
        val oneUi = props.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
        val brand = props.getOrNull(4).orEmpty().trim()
        val serial = props.getOrNull(5).orEmpty().trim()
        val name = market.ifEmpty { model.ifEmpty { brand.replaceFirstChar { it.uppercase() }.ifEmpty { "Telefono" } } }
        val details = buildString {
            if (oneUi > 0) append("One UI ${oneUiLabel(oneUi)} · ")
            append("Android $android")
            if (market.isNotEmpty() && model.isNotEmpty() && market != model) append(" · $model")
        }
        prefs.updateCurrent {
            it.copy(
                name = name,
                info = details,
                brand = brand,
                oneUi = oneUi,
                serial = it.serial.ifEmpty { serial },
            )
        }
        return DeviceInfo(name, details, oneUi, brand)
    }

    /**
     * Attiva le opzioni di Android per finestre libere e modalità desktop sui display secondari.
     * Servono sui telefoni non Samsung perché sul display virtuale compaia un desktop; si applicano
     * dopo un riavvio del telefono.
     */
    suspend fun prepareDesktopMode(context: Context) = withContext(Dispatchers.IO) {
        connect(context)
        val adb = PonteAdb.get(context)
        adb.shell(
            "settings put global enable_freeform_support 1; " +
                "settings put global force_resizable_activities 1; " +
                "settings put global force_desktop_mode_on_external_displays 1",
        )
        Unit
    }

    /** 80000 → "8", 80500 → "8.5" */
    fun oneUiLabel(value: Int): String {
        val major = value / 10000
        val minor = (value / 100) % 100
        return if (minor == 0) "$major" else "$major.${minor / 10}"
    }
}
