package it.giuseppe.ponte.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.giuseppe.ponte.Codec
import it.giuseppe.ponte.Prefs
import it.giuseppe.ponte.Resolution
import it.giuseppe.ponte.Diag
import it.giuseppe.ponte.adb.GalaxyLink
import it.giuseppe.ponte.scrcpy.SessionState
import it.giuseppe.ponte.session.SessionManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HomeScreen(onStart: () -> Unit, onAddDevice: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Ponte", fontWeight = FontWeight.SemiBold) },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Altro")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Esporta registro") },
                            onClick = { menu = false; Diag.share(context) },
                        )
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { HeroCard(prefs, onStart = onStart, onPair = onAddDevice) }
                if (prefs.devices.size > 1) {
                    item { DevicesRow(prefs) }
                }
                item { ControlCard(prefs) }
                item { ScreenCard(prefs) }
                item { StreamCard(prefs) }
                item { GuideCard() }
            }
        }
    }

}

@Composable
private fun HeroCard(prefs: Prefs, onStart: () -> Unit, onPair: () -> Unit) {
    val status by SessionManager.status.collectAsState()
    val active = status !is SessionState.Closed
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (prefs.isPaired) Icons.Rounded.PhoneAndroid else Icons.Rounded.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (prefs.isPaired) prefs.deviceName.ifEmpty { "Telefono associato" } else "Nessun telefono associato",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (prefs.isPaired) prefs.deviceInfo.ifEmpty { prefs.lastHost } else "Associalo una volta sola con il codice del Debug wireless",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            if (prefs.isPaired) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.DesktopWindows, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(if (active) "Torna al desktop" else prefs.startLabel, style = MaterialTheme.typography.titleMedium)
                }
                if (prefs.isGenericDesktop) {
                    Spacer(Modifier.height(12.dp))
                    DesktopPrepButton()
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { prefs.forgetDevice() }) { Text("Dimentica") }
                    TextButton(onClick = onPair) { Text("Aggiungi telefono") }
                }
            } else {
                Button(
                    onClick = onPair,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Associa il telefono", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Telefoni associati: si sceglie con un tocco quale usare. */
@Composable
private fun DevicesRow(prefs: Prefs) {
    val selected = prefs.current?.id
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prefs.devices.forEach { device ->
            FilterChip(
                selected = device.id == selected,
                onClick = { prefs.select(device.id) },
                label = { Text(device.name.ifEmpty { device.host.ifEmpty { "Telefono" } }) },
                leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun ControlCard(prefs: Prefs) {
    SectionCard(Icons.Rounded.TouchApp, "Controllo") {
        val options = listOf(false to "Tocco diretto", true to "Touchpad")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = prefs.touchpad == value,
                    onClick = { prefs.touchpad = value },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (prefs.touchpad) {
                "Lo schermo funziona come il trackpad di un portatile: un dito muove il puntatore, tocco = clic, " +
                    "tocco con due dita = clic destro, due dita trascinate = scorrimento, doppio tocco tenendo premuto = trascina."
            } else {
                "Tocchi il desktop direttamente, come su un tablet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Tastiera", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val keyboards = listOf(false to "Del telefono", true to "Di questo dispositivo")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            keyboards.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = prefs.localKeyboard == value,
                    onClick = { prefs.localKeyboard = value },
                    shape = SegmentedButtonDefaults.itemShape(i, keyboards.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (prefs.localKeyboard) {
                "La tastiera del telefono collegato non compare: scrivi con quella di questo dispositivo dal pulsante Tastiera dei comandi."
            } else {
                "Toccando un campo di testo compare la tastiera del telefono collegato, dentro il desktop."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Sui telefoni non Samsung: attiva finestre libere e desktop sui display secondari (poi va riavviato). */
@Composable
private fun DesktopPrepButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    OutlinedButton(
        onClick = {
            busy = true
            message = null
            scope.launch {
                message = try {
                    GalaxyLink.prepareDesktopMode(context)
                    "Fatto. Riavvia il telefono una volta perché la modalità desktop si attivi."
                } catch (e: Exception) {
                    e.message ?: "Non è stato possibile cambiare le impostazioni."
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Tune, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (busy) "Attendi…" else "Prepara la modalità desktop")
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ScreenCard(prefs: Prefs) {
    val context = LocalContext.current
    val auto = remember { SessionManager.autoDpi(context) }
    val metrics = context.resources.displayMetrics
    val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
    val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
    val dpi = if (prefs.dpi > 0) prefs.dpi else auto

    SectionCard(Icons.Rounded.Tune, "Schermo") {
        Text("Dimensione di testo e icone", style = MaterialTheme.typography.titleSmall)
        Text(
            "Densità $dpi dpi${if (prefs.dpi == 0) " (automatica)" else ""} · spazio di lavoro " +
                "${longSide * 160 / dpi}×${shortSide * 160 / dpi} dp",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = dpi.toFloat(),
            onValueChange = { prefs.dpi = (it / 10f).roundToInt() * 10 },
            valueRange = 120f..400f,
            steps = 27,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Più spazio", style = MaterialTheme.typography.labelMedium)
            if (prefs.dpi != 0) {
                TextButton(onClick = { prefs.dpi = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Automatica")
                }
            }
            Text("Più grande", style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(12.dp))
        Text("Risoluzione", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val options = Resolution.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = prefs.resolution == option,
                    onClick = { prefs.resolution = option },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) {
                    Text(if (option == Resolution.FULL) "Piena (${longSide}×$shortSide)" else "Ridotta, più fluida")
                }
            }
        }
    }
}

@Composable
private fun StreamCard(prefs: Prefs) {
    SectionCard(Icons.Rounded.CastConnected, "Trasmissione") {
        Text("Qualità video", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val rates = listOf(8 to "Leggera", 12 to "Bilanciata", 20 to "Alta")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            rates.forEachIndexed { i, (mbps, label) ->
                SegmentedButton(
                    selected = prefs.bitRateMbps == mbps,
                    onClick = { prefs.bitRateMbps = mbps },
                    shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Codifica", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        val codecs = Codec.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            codecs.forEachIndexed { i, codec ->
                SegmentedButton(
                    selected = prefs.codec == codec,
                    onClick = { prefs.codec = codec },
                    shape = SegmentedButtonDefaults.itemShape(i, codecs.size),
                ) { Text(codec.label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SwitchRow(
            title = "Connessione diretta",
            subtitle = "Video su un canale TCP dedicato invece che dentro ADB: molto più fluido. Se la rete lo blocca, Ponte torna da solo ad ADB.",
            checked = prefs.directChannel,
            onChange = { prefs.directChannel = it },
        )
        SwitchRow(
            title = "Audio su questo dispositivo",
            subtitle = "Il suono esce da qui invece che dal telefono",
            checked = prefs.audio,
            onChange = { prefs.audio = it },
        )
        SwitchRow(
            title = "Tieni sveglio il telefono",
            subtitle = "Evita che il desktop si metta in pausa quando il telefono va in standby",
            checked = prefs.keepActive,
            onChange = { prefs.keepActive = it },
        )
    }
}

@Composable
private fun GuideCard() {
    SectionCard(Icons.Rounded.Wifi, "Prima di iniziare") {
        val steps = listOf(
            "Galaxy con One UI 8 o successiva: DeX parte da solo. Altri telefoni: compare la modalità desktop di Android, se disponibile, oppure apri le app dalla linguetta in alto.",
            "I due dispositivi devono essere sulla stessa rete Wi-Fi.",
            "Sul telefono da usare: Impostazioni → Opzioni sviluppatore → Debug wireless attivo.",
            "Per l'associazione: Debug wireless → Associa dispositivo con codice, poi inserisci il codice qui.",
            "Utile: aggiungi il riquadro rapido \"Debug wireless\" dalle Opzioni sviluppatore.",
        )
        steps.forEachIndexed { i, step ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
