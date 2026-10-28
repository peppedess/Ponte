package it.giuseppe.ponte.adb

import android.content.Context
import io.github.muntashirakon.adb.AdbPairingRequiredException
import it.giuseppe.ponte.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class LinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Associazione e connessione al Galaxy tramite Debug wireless. */
object GalaxyLink {

    data class DeviceInfo(val name: String, val details: String, val oneUi: Int, val brand: String) {
        val isSamsung: Boolean get() = brand.equals("samsung", ignoreCase = true)
    }

    /** Associa Ponte al Galaxy con il codice a 6 cifre e verifica subito la connessione. */
    suspend fun pair(context: Context, host: String, port: Int, code: String, serial: String) =
        withContext(Dispatchers.IO) {
            val adb = PonteAdb.get(context)
            try {
                adb.pair(host, port, code.trim())
            } catch (e: Exception) {
                throw LinkException("Associazione non riuscita: controlla il codice e riprova.", e)
            }
            val prefs = Prefs.get(context)
            prefs.deviceSerial = serial
            prefs.lastHost = host
            prefs.lastPort = 0
            val info = connect(context)
            info
        }

    /**
     * Si collega al Galaxy associato: prima prova l'ultimo indirizzo noto (immediato), poi cerca
     * in rete la porta attuale del Debug wireless (cambia ogni volta che viene riattivato).
     */
    suspend fun connect(context: Context): DeviceInfo = withContext(Dispatchers.IO) {
        val adb = PonteAdb.get(context)
        val prefs = Prefs.get(context)

        if (adb.isConnected && runCatching { adb.shell("echo ok") == "ok" }.getOrDefault(false)) {
            return@withContext readInfo(adb, prefs)
        }
        adb.disconnectQuietly()

        var lastError: Throwable? = null
        if (prefs.lastHost.isNotEmpty() && prefs.lastPort > 0) {
            try {
                if (adb.connect(prefs.lastHost, prefs.lastPort)) return@withContext readInfo(adb, prefs)
            } catch (e: AdbPairingRequiredException) {
                throw LinkException("Il telefono non riconosce più Ponte: associalo di nuovo.", e)
            } catch (e: Exception) {
                lastError = e
                adb.disconnectQuietly()
            }
        }

        val serial = prefs.deviceSerial
        val service = AdbDiscovery(context).findConnectService(8_000) { s ->
            when {
                serial.isNotEmpty() -> s.serial == serial || s.host == prefs.lastHost
                else -> s.host == prefs.lastHost
            }
        } ?: throw LinkException(
            "Telefono non trovato. Attiva Opzioni sviluppatore → Debug wireless " +
                "e verifica che i due dispositivi siano sulla stessa rete Wi-Fi.",
            lastError,
        )

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
        prefs.lastHost = service.host
        prefs.lastPort = service.port
        readInfo(adb, prefs)
    }

    private fun readInfo(adb: PonteAdb, prefs: Prefs): DeviceInfo {
        val props = adb.shell(
            "echo \"$(getprop ro.product.marketname)|$(getprop ro.product.model)|" +
                "$(getprop ro.build.version.release)|$(getprop ro.build.version.oneui)|" +
                "$(getprop ro.product.manufacturer)\"",
        ).split('|')
        val market = props.getOrNull(0).orEmpty().trim()
        val model = props.getOrNull(1).orEmpty().trim()
        val android = props.getOrNull(2).orEmpty().trim()
        val oneUi = props.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
        val brand = props.getOrNull(4).orEmpty().trim()
        val name = market.ifEmpty { model.ifEmpty { brand.replaceFirstChar { it.uppercase() }.ifEmpty { "Telefono" } } }
        val details = buildString {
            if (oneUi > 0) append("One UI ${oneUiLabel(oneUi)} · ")
            append("Android $android")
            if (market.isNotEmpty() && model.isNotEmpty() && market != model) append(" · $model")
        }
        prefs.deviceName = name
        prefs.deviceInfo = details
        prefs.deviceBrand = brand
        prefs.oneUi = oneUi
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
