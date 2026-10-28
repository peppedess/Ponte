// Piccolo programma che gira SUL TELEFONO collegato (con i permessi della shell ADB, via app_process):
// fa da ponte tra un socket TCP sulla rete locale e il socket locale di scrcpy-server.
// Il video così non passa dentro la connessione ADB, che è molto più lenta.
plugins {
    id("com.android.application")
}

android {
    namespace = "it.giuseppe.ponte.relay"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.giuseppe.ponte.relay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
