package it.giuseppe.ponte

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class Resolution(val label: String, val scale: Float) {
    FULL("Piena", 1f),
    REDUCED("Ridotta", 0.75f),
}

enum class Codec(val label: String, val serverName: String, val mime: String) {
    H264("H.264", "h264", "video/avc"),
    H265("H.265", "h265", "video/hevc"),
}

/** Impostazioni e Galaxy associato, osservabili da Compose. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    // Galaxy associato
    var deviceSerial by stringPref("serial", "")
    var deviceName by stringPref("name", "")
    var deviceInfo by stringPref("info", "")
    var lastHost by stringPref("host", "")
    var lastPort by intPref("port", 0)
    var deviceBrand by stringPref("brand", "")
    var oneUi by intPref("oneui", 0)

    // Schermo e streaming
    var dpi by intPref("dpi", 0) // 0 = automatico
    var resolution by enumPref("resolution", Resolution.FULL)
    var bitRateMbps by intPref("bitrate", 12)
    var codec by enumPref("codec", Codec.H264)
    var audio by boolPref("audio", true)
    var keepActive by boolPref("keep_active", true)

    val isPaired: Boolean get() = deviceSerial.isNotEmpty() || lastHost.isNotEmpty()

    /** Samsung con One UI 8+: sul display virtuale parte DeX. */
    val isSamsungDex: Boolean get() = deviceBrand.equals("samsung", ignoreCase = true) && oneUi >= 80000

    /** Marca nota ma non Samsung DeX: sul display virtuale compare la modalità desktop di Android (se c'è). */
    val isGenericDesktop: Boolean get() = deviceBrand.isNotEmpty() && !isSamsungDex

    val startLabel: String get() = if (isGenericDesktop) "Avvia desktop" else "Avvia DeX"

    fun forgetDevice() {
        deviceSerial = ""
        deviceName = ""
        deviceInfo = ""
        lastHost = ""
        lastPort = 0
        deviceBrand = ""
        oneUi = 0
    }

    private fun stringPref(key: String, default: String) =
        Pref(sp.getString(key, default) ?: default) { sp.edit { putString(key, it) } }

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
