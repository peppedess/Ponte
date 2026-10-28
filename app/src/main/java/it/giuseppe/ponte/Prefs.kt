package it.giuseppe.ponte

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Resolution(val label: String, val scale: Float) {
    FULL("Piena", 1f),
    REDUCED("Ridotta", 0.75f),
}

enum class Codec(val label: String, val serverName: String, val mime: String) {
    H264("H.264", "h264", "video/avc"),
    H265("H.265", "h265", "video/hevc"),
}

/** Un telefono associato a Ponte. */
data class SavedDevice(
    val id: String = UUID.randomUUID().toString(),
    val serial: String = "",
    val name: String = "",
    val info: String = "",
    val host: String = "",
    val port: Int = 0,
    val brand: String = "",
    val oneUi: Int = 0,
) {
    val isSamsungDex: Boolean get() = brand.equals("samsung", ignoreCase = true) && oneUi >= 80000

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("serial", serial).put("name", name).put("info", info)
        .put("host", host).put("port", port).put("brand", brand).put("oneui", oneUi)

    companion object {
        fun fromJson(o: JSONObject) = SavedDevice(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            serial = o.optString("serial"),
            name = o.optString("name"),
            info = o.optString("info"),
            host = o.optString("host"),
            port = o.optInt("port"),
            brand = o.optString("brand"),
            oneUi = o.optInt("oneui"),
        )
    }
}

/** Impostazioni e telefoni associati, osservabili da Compose. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    // ---- Telefoni associati ----

    var devices by mutableStateOf(loadDevices())
        private set

    private var selectedIdState by mutableStateOf(sp.getString("selected", null) ?: devices.firstOrNull()?.id.orEmpty())

    val current: SavedDevice? get() = devices.firstOrNull { it.id == selectedIdState } ?: devices.firstOrNull()

    fun select(id: String) {
        selectedIdState = id
        sp.edit { putString("selected", id) }
    }

    /** Aggiunge un telefono appena associato (o aggiorna quello con lo stesso seriale/indirizzo) e lo seleziona. */
    fun addPairedDevice(serial: String, host: String) {
        val existing = devices.firstOrNull { (serial.isNotEmpty() && it.serial == serial) || (serial.isEmpty() && it.host == host) }
        val device = (existing ?: SavedDevice()).copy(serial = serial.ifEmpty { existing?.serial.orEmpty() }, host = host, port = 0)
        saveDevices(if (existing != null) devices.map { if (it.id == existing.id) device else it } else devices + device)
        select(device.id)
    }

    fun updateCurrent(transform: (SavedDevice) -> SavedDevice) {
        val target = current ?: SavedDevice().also { select(it.id) }
        val updated = transform(target)
        saveDevices(if (devices.any { it.id == target.id }) devices.map { if (it.id == target.id) updated else it } else devices + updated)
    }

    fun forgetDevice() {
        val target = current ?: return
        saveDevices(devices.filterNot { it.id == target.id })
        select(devices.firstOrNull()?.id.orEmpty())
    }

    private fun saveDevices(list: List<SavedDevice>) {
        devices = list
        sp.edit { putString("devices", JSONArray(list.map { it.toJson() }).toString()) }
    }

    private fun loadDevices(): List<SavedDevice> {
        val json = sp.getString("devices", null)
        if (json != null) {
            return runCatching {
                val array = JSONArray(json)
                List(array.length()) { SavedDevice.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        // Versioni precedenti: un solo telefono salvato in chiavi separate
        val host = sp.getString("host", "").orEmpty()
        val serial = sp.getString("serial", "").orEmpty()
        if (host.isEmpty() && serial.isEmpty()) return emptyList()
        val migrated = SavedDevice(
            serial = serial,
            name = sp.getString("name", "").orEmpty(),
            info = sp.getString("info", "").orEmpty(),
            host = host,
            port = sp.getInt("port", 0),
            brand = sp.getString("brand", "").orEmpty(),
            oneUi = sp.getInt("oneui", 0),
        )
        sp.edit { putString("devices", JSONArray(listOf(migrated.toJson())).toString()) }
        return listOf(migrated)
    }

    // Accesso rapido al telefono selezionato
    val deviceSerial: String get() = current?.serial.orEmpty()
    val deviceName: String get() = current?.name.orEmpty()
    val deviceInfo: String get() = current?.info.orEmpty()
    val lastHost: String get() = current?.host.orEmpty()
    val lastPort: Int get() = current?.port ?: 0
    val deviceBrand: String get() = current?.brand.orEmpty()

    // ---- Schermo, trasmissione, controllo ----

    var dpi by intPref("dpi", 0) // 0 = automatico
    var resolution by enumPref("resolution", Resolution.FULL)
    var bitRateMbps by intPref("bitrate", 12)
    var codec by enumPref("codec", Codec.H264)
    var audio by boolPref("audio", true)
    var keepActive by boolPref("keep_active", true)
    /** Lo schermo funziona come il trackpad di un portatile invece che come touchscreen. */
    var touchpad by boolPref("touchpad", false)

    val isPaired: Boolean get() = current != null

    /** Samsung con One UI 8+: sul display virtuale parte DeX. */
    val isSamsungDex: Boolean get() = current?.isSamsungDex == true

    /** Marca nota ma non Samsung DeX: sul display virtuale compare la modalità desktop di Android (se c'è). */
    val isGenericDesktop: Boolean get() = deviceBrand.isNotEmpty() && !isSamsungDex

    val startLabel: String get() = if (isGenericDesktop) "Avvia desktop" else "Avvia DeX"

    private fun intPref(key: String, default: Int) =
        Pref(sp.getInt(key, default)) { sp.edit { putInt(key, it) } }

    private fun boolPref(key: String, default: Boolean) =
        Pref(sp.getBoolean(key, default)) { sp.edit { putBoolean(key, it) } }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): Pref<E> {
        val stored = sp.getString(key, null)
        val initial = enumValues<E>().firstOrNull { it.name == stored } ?: default
        return Pref(initial) { sp.edit { putString(key, it.name) } }
    }

    class Pref<T>(initial: T, private val save: (T) -> Unit) {
        private var state by mutableStateOf(initial)

        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = state

        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            state = value
            save(value)
        }
    }

    companion object {
        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
