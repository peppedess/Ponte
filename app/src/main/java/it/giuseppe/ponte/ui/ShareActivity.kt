package it.giuseppe.ponte.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import it.giuseppe.ponte.Diag
import it.giuseppe.ponte.Prefs
import it.giuseppe.ponte.adb.AdbSync
import it.giuseppe.ponte.adb.GalaxyLink
import it.giuseppe.ponte.adb.PonteAdb
import it.giuseppe.ponte.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Condividi → Ponte": invia file da questo dispositivo ai Download del telefono collegato
 * (cartella Download/Ponte), dove li trovi subito anche dal desktop.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val target = Prefs.get(this).current?.name?.ifEmpty { null } ?: "telefono"

        setContent {
            PonteTheme {
                var progress by remember { mutableFloatStateOf(0f) }
                var status by remember { mutableStateOf("Connessione al $target…") }
                var done by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    if (uris.isEmpty()) {
                        error = "Nessun file da inviare."
                        return@LaunchedEffect
                    }
                    if (!Prefs.get(this@ShareActivity).isPaired) {
                        error = "Associa prima un telefono in Ponte."
                        return@LaunchedEffect
                    }
                    try {
                        GalaxyLink.connect(this@ShareActivity)
                        val sizes = uris.map { querySize(it) }
                        val total = sizes.sum().coerceAtLeast(1L)
                        var before = 0L
                        uris.forEachIndexed { index, uri ->
                            val name = safeName(queryName(uri) ?: "file-${System.currentTimeMillis()}")
                            status = "Invio di $name (${index + 1} di ${uris.size})…"
                            val path = "$REMOTE_DIR/$name"
                            withContext(Dispatchers.IO) {
                                val adb = PonteAdb.get(this@ShareActivity)
                                adb.shell("mkdir -p '$REMOTE_DIR'")
                                contentResolver.openInputStream(uri)?.use { input ->
                                    AdbSync.push(adb, input.buffered(256 * 1024), path, 420) { sent ->
                                        progress = ((before + sent).toFloat() / total).coerceIn(0f, 1f)
                                    }
                                } ?: throw IllegalStateException("Impossibile leggere $name")
                                notifyMediaScanner(adb, path)
                            }
                            before += sizes[index]
                            Diag.i("invio", "Inviato $name (${sizes[index]} byte)")
                        }
                        progress = 1f
                        status = if (uris.size == 1) "File inviato in Download/Ponte" else "${uris.size} file inviati in Download/Ponte"
                        done = true
                    } catch (e: Exception) {
                        Diag.e("invio", "Invio non riuscito", e)
                        error = e.message ?: "Invio non riuscito"
                    }
                }

                AlertDialog(
                    onDismissRequest = { if (done || error != null) finish() },
                    icon = {
                        Icon(
                            when {
                                error != null -> Icons.Rounded.ErrorOutline
                                done -> Icons.Rounded.CheckCircle
                                else -> Icons.Rounded.UploadFile
                            },
                            contentDescription = null,
                        )
                    },
                    title = { Text(if (error != null) "Invio non riuscito" else "Invio al $target") },
                    text = {
                        Column(Modifier.fillMaxWidth()) {
                            Text(error ?: status)
                            if (error == null) {
                                Spacer(Modifier.height(16.dp))
                                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { finish() }) { Text(if (done || error != null) "Chiudi" else "Annulla") }
                    },
                )
            }
        }
    }

    /** Fa comparire il file nelle app (Galleria, File) del telefono collegato. */
    private fun notifyMediaScanner(adb: PonteAdb, path: String) {
        val session = SessionManager.session.value
        if (session != null) {
            session.scanFile(path)
        } else {
            runCatching {
                adb.shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$path' > /dev/null")
            }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L

    /** Niente caratteri che diano problemi alla shell o al file system. */
    private fun safeName(name: String): String =
        name.replace(Regex("""[/\\'"`$\n\r]"""), "_").trim().ifEmpty { "file" }.take(120)

    companion object {
        private const val REMOTE_DIR = "/sdcard/Download/Ponte"
    }
}
