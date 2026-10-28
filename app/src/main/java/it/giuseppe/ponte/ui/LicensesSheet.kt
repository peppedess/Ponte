package it.giuseppe.ponte.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Component(val name: String, val author: String, val license: String, val url: String)

private val components = listOf(
    Component("scrcpy-server 5.0.1", "Genymobile, Romain Vimont", "Apache License 2.0", "https://github.com/Genymobile/scrcpy"),
    Component("libadb-android", "Muntashir Al-Islam", "Apache License 2.0 (doppia licenza con GPL-3.0)", "https://github.com/MuntashirAkon/libadb-android"),
    Component("spake2-java", "Muntashir Al-Islam", "Apache License 2.0", "https://github.com/MuntashirAkon/spake2-java"),
    Component("Conscrypt", "Google", "Apache License 2.0", "https://github.com/google/conscrypt"),
    Component("Bouncy Castle", "Legion of the Bouncy Castle", "Bouncy Castle Licence (MIT)", "https://www.bouncycastle.org/licence.html"),
    Component("AndroidX, Jetpack Compose, Material 3", "Google / AOSP", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    Component("Kotlin, kotlinx.coroutines", "JetBrains", "Apache License 2.0", "https://kotlinlang.org"),
)

/** Licenze dei componenti di terze parti e informazioni sull'app. */
@Composable
fun LicensesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.padding(horizontal = 24.dp)) {
            item {
                Text("Ponte $version", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Il desktop del telefono su un altro schermo Android, sulla rete di casa. Nessun dato lascia la tua rete " +
                        "locale e Ponte non raccoglie informazioni.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Samsung, Galaxy e DeX sono marchi di Samsung Electronics Co., Ltd. Android e Pixel sono marchi di " +
                        "Google LLC. Ponte è un progetto indipendente, non affiliato né approvato da queste aziende.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Text("Componenti open source", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            items(components) { c ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)))
                            }
                        }
                        .padding(vertical = 12.dp),
                ) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${c.author} · ${c.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Il testo completo della Apache License 2.0 è su apache.org/licenses/LICENSE-2.0. scrcpy-server è " +
                        "incluso senza modifiche, come pubblicato dagli autori.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
