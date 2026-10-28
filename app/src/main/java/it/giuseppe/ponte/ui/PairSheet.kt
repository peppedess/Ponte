package it.giuseppe.ponte.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.giuseppe.ponte.adb.AdbDiscovery
import it.giuseppe.ponte.adb.AdbService
import it.giuseppe.ponte.adb.GalaxyLink
import kotlinx.coroutines.launch

@Composable
fun PairSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val discovery = remember { AdbDiscovery(context) }

    var services by remember { mutableStateOf(emptyList<AdbService>()) }
    var selected by remember { mutableStateOf<AdbService?>(null) }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<GalaxyLink.DeviceInfo?>(null) }

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
    val canPair = !busy && target != null && code.length == 6

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            val done = success
            if (done != null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("${done.name} associato", style = MaterialTheme.typography.headlineSmall)
                    Text(done.details, style = MaterialTheme.typography.bodyMedium)
                    if (done.oneUi in 1..79_999) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Attenzione: DeX su display virtuale richiede One UI 8 o successiva. " +
                                "Con questa versione vedrai il normale schermo Android invece di DeX.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onDismiss, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text("Fatto")
                    }
                }
                return@Column
            }

            Text("Associa il Galaxy", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Sul Galaxy apri Opzioni sviluppatore → Debug wireless → " +
                    "Associa dispositivo con codice di associazione. Lascia aperta quella schermata.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            AnimatedContent(targetState = manual, label = "modalità") { isManual ->
                if (isManual) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                } else if (services.isEmpty()) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LoadingIndicator(Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Cerco il Galaxy sulla rete…", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        services.forEach { service ->
                            val isSelected = service == selected
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = service },
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Dispositivo ${service.serial}", style = MaterialTheme.typography.bodyLarge)
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

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val t = target ?: return@Button
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            success = GalaxyLink.pair(context, t.first, t.second, code, t.third)
                        } catch (e: Exception) {
                            error = e.message ?: "Associazione non riuscita"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = canPair,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (busy) LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Associa")
                }
            }
        }
    }
}
