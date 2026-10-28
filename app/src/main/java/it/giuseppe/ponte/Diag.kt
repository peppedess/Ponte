package it.giuseppe.ponte

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro diagnostico in memoria (ultime righe di app e server), esportabile come file di testo
 * da allegare a una segnalazione.
 */
object Diag {

    private const val MAX_LINES = 3000
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)

    fun i(tag: String, message: String) = add("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = add("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = add("E", tag, message, error)

    private fun add(level: String, tag: String, message: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e("Ponte/$tag", message, error)
            "W" -> Log.w("Ponte/$tag", message, error)
            else -> Log.i("Ponte/$tag", message)
        }
        val line = buildString {
            append(time.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (error != null) {
                append("\n    ").append(error.javaClass.name).append(": ").append(error.message)
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 4) {
                    append("\n    causa: ").append(cause.javaClass.name).append(": ").append(cause.message)
                    cause = cause.cause
                    depth++
                }
            }
        }
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /** Scrive il registro in un file e apre la condivisione di Android. */
    fun share(context: Context) {
        val prefs = Prefs.get(context)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val header = buildString {
            appendLine("Registro di Ponte $version")
            appendLine("Data: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(Date())}")
            appendLine("Questo dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            prefs.current?.let { d ->
                appendLine("Telefono collegato: ${d.name} · ${d.info} · ${d.brand}")
            }
            appendLine(
                "Impostazioni: dpi=${prefs.dpi} risoluzione=${prefs.resolution} bitrate=${prefs.bitRateMbps}M " +
                    "codec=${prefs.codec} audio=${prefs.audio} touchpad=${prefs.touchpad} diretto=${prefs.directChannel}",
            )
            appendLine("----")
        }
        val file = File(context.cacheDir, "registri/registro-ponte.txt")
        file.parentFile?.mkdirs()
        file.writeText(header + snapshot().joinToString("\n"))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Registro di Ponte")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Esporta registro").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
