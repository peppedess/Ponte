package it.giuseppe.ponte

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import it.giuseppe.ponte.ui.HomeScreen
import it.giuseppe.ponte.ui.Onboarding
import it.giuseppe.ponte.ui.PonteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot delle schermate principali, pubblicati dalla CI sul ramo "rapporto". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "it-rIT-w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { PonteTheme { content() } }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun pairDevice() {
        val prefs = Prefs.get(ApplicationProvider.getApplicationContext())
        if (prefs.devices.isEmpty()) {
            prefs.addPairedDevice("R5CX1234", "192.168.1.20")
            prefs.updateCurrent {
                it.copy(name = "Galaxy S24", info = "One UI 8.5 · Android 16 · SM-S921B", brand = "samsung", oneUi = 80500)
            }
        }
    }

    @Test fun onboardingWelcome() = shoot("01_benvenuto") {
        Onboarding(firstRun = true, onDone = {}, onCancel = {})
    }

    @Test fun home() {
        pairDevice()
        shoot("02_home") { HomeScreen(onStart = {}, onAddDevice = {}) }
    }

    @Test
    @Config(qualifiers = "it-rIT-w1280dp-h800dp-xhdpi")
    fun homeTablet() {
        pairDevice()
        shoot("03_home_tablet") { HomeScreen(onStart = {}, onAddDevice = {}) }
    }
}
