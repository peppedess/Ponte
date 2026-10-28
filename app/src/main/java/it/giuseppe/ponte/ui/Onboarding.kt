package it.giuseppe.ponte.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.giuseppe.ponte.Diag
import it.giuseppe.ponte.adb.AdbDiscovery
import it.giuseppe.ponte.adb.AdbService
import it.giuseppe.ponte.adb.GalaxyLink
import kotlinx.coroutines.launch

/**
 * Configurazione guidata: benvenuto, opzioni sviluppatore, associazione con il codice, fatto.
 * Per aggiungere un altro telefono si parte direttamente dall'associazione.
 */
@Composable
fun Onboarding(firstRun: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val steps = if (firstRun) 4 else 2
    var step by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<GalaxyLink.DeviceInfo?>(null) }

    BackHandler {
        if (step > 0 && result == null) step-- else onCancel()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(if (firstRun) "Salta" else "Annulla") }
            Spacer(Modifier.weight(1f))
            Text("${step + 1} di $steps", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(16.dp))
        }
        LinearWavyProgressIndicator(
            progress = { (step + 1f) / steps },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
            },
            label = "passo",
            modifier = Modifier.weight(1f),
        ) { current ->
            val kind = if (firstRun) current else current + 2
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.widthIn(max = 520.dp)) {
                    when (kind) {
                        0 -> WelcomeStep(onNext = { step++ })
                        1 -> DeveloperStep(onNext = { step++ })
                        2 -> PairStep(onPaired = { info ->
                            result = info
                            step++
                        })
                        else -> DoneStep(result, onDone)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, text: String) {
    Surface(
        shape = MaterialShapes.Cookie9Sided.toShape(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(96.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp))
        }
    }
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun NumberedLine(number: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepHeader(
        Icons.Rounded.DesktopWindows,
        "Il desktop del telefono, qui",
        "Ponte mostra su questo schermo Samsung DeX del tuo Galaxy (One UI 8 o successiva) o la modalità " +
            "desktop di altri telefoni Android. Tutto avviene sulla tua rete Wi-Fi, senza cavi, senza PC e senza root.",
    )
    NumberedLine(1, "Attivi le Opzioni sviluppatore sul telefono da usare.")
    NumberedLine(2, "Associ Ponte una volta sola con un codice a 6 cifre.")
    NumberedLine(3, "Da lì in poi basta un tocco su \"Avvia\".")
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Iniziamo")
    }
}

@Composable
private fun DeveloperStep(onNext: () -> Unit) {
    StepHeader(
        Icons.Rounded.DeveloperMode,
        "Opzioni sviluppatore",
        "Sul telefono da usare come sorgente (non su questo) servono le Opzioni sviluppatore. Se le hai già, vai avanti.",
    )
    Text("Galaxy", style = MaterialTheme.typography.titleMedium)
    NumberedLine(1, "Impostazioni → Info sul telefono → Informazioni software.")
    NumberedLine(2, "Tocca 7 volte \"Numero build\" e conferma con il PIN.")
    Spacer(Modifier.height(16.dp))
    Text("Pixel e altri", style = MaterialTheme.typography.titleMedium)
    NumberedLine(1, "Impostazioni → Info sul telefono.")
    NumberedLine(2, "Tocca 7 volte \"Numero build\".")
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Fatto, avanti")
    }
}

@Composable
private fun PairStep(onPaired: (GalaxyLink.DeviceInfo) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { AdbDiscovery(context) }

    var services by remember { mutableStateOf(emptyList<AdbService>()) }
    var selected by remember { mutableStateOf<AdbService?>(null) }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        discovery.services(AdbDiscovery.TYPE_PAIRING).collect { list ->
            services = list
            if (selected == null || selected !in list) selected = list.firstOrNull()
        }
    }

    val target: Triple<String, Int, String>? = if (manual) {
        manualPort.toIntOrNull()?.takeIf { manualHost.isNotBlank() }?.let { Triple(manualHost.trim(), it, "") }
    } else {
        selected?.let { Triple(it.host, it.port, it.serial) }
    }

    StepHeader(
        Icons.Rounded.Wifi,
        "Associa il telefono",
        "Sul telefono da usare: Opzioni sviluppatore → Debug wireless (attivalo) → " +
            "\"Associa dispositivo con codice di associazione\". Lascia aperta quella schermata.",
    )

    AnimatedContent(targetState = manual to services.isEmpty(), label = "ricerca") { (isManual, empty) ->
        when {
            isManual -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("Indirizzo IP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1.6f),
                )
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Porta") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            empty -> Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Cerco il telefono sulla rete…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                services.forEach { service ->
                    val isSelected = service == selected
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth().clickable { selected = service },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Telefono ${service.serial}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${service.host}:${service.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isSelected) Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    TextButton(onClick = { manual = !manual }) {
        Text(if (manual) "Cerca automaticamente" else "Inserisci indirizzo e porta a mano")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text("Codice di associazione Wi-Fi") },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    AnimatedVisibility(error != null) {
        Text(
            error.orEmpty(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            val t = target ?: return@Button
            busy = true
            error = null
            scope.launch {
                try {
                    onPaired(GalaxyLink.pair(context, t.first, t.second, code, t.third))
                } catch (e: Exception) {
                    Diag.e("associazione", "Associazione non riuscita", e)
                    error = e.message ?: "Associazione non riuscita"
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy && target != null && code.length == 6,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
        else Text("Associa")
    }
}

@Composable
private fun DoneStep(info: GalaxyLink.DeviceInfo?, onDone: () -> Unit) {
    StepHeader(
        Icons.Rounded.CheckCircle,
        "${info?.name ?: "Telefono"} associato",
        info?.details.orEmpty(),
    )
    if (info != null) {
        val note = when {
            info.isSamsung && info.oneUi in 1..79_999 ->
                "DeX su display virtuale richiede One UI 8 o successiva: con questa versione vedrai il normale schermo Android."
            info.isSamsung -> "Con \"Avvia DeX\" il desktop DeX del Galaxy compare qui."
            else -> "Non è un Samsung: al posto di DeX vedrai la modalità desktop di Android, se il telefono la supporta. " +
                "Dalla schermata principale puoi attivarla con \"Prepara la modalità desktop\"."
        }
        Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            color = if (info.isSamsung && info.oneUi in 1..79_999) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
    Spacer(Modifier.height(32.dp))
    Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Inizia a usarlo")
    }
}
