package it.giuseppe.ponte.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import it.giuseppe.ponte.Prefs

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        val prefs = Prefs.get(this)
        setContent {
            PonteTheme {
                // null = schermata principale; true = prima configurazione; false = aggiunta di un telefono
                var onboarding by remember { mutableStateOf(if (prefs.isPaired) null else true) }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    AnimatedContent(
                        targetState = onboarding,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "schermata",
                    ) { mode ->
                        if (mode == null) {
                            HomeScreen(
                                onStart = { startActivity(Intent(this, DexActivity::class.java)) },
                                onAddDevice = { onboarding = !prefs.isPaired },
                            )
                        } else {
                            Onboarding(
                                firstRun = mode,
                                onDone = { onboarding = null },
                                onCancel = { onboarding = null },
                            )
                        }
                    }
                }
            }
        }
    }

    /** La notifica della sessione mostra "Torna al desktop" e "Termina": su Android 13+ serve il permesso. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
