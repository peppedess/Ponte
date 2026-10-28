import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "it.giuseppe.ponte"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.giuseppe.ponte"
        minSdk = 26
        targetSdk = 36
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("ponte") {
            storeFile = file("ponte.jks")
            storePassword = "ponte123"
            keyAlias = "ponte"
            keyPassword = "ponte123"
        }
    }

    buildTypes {
        release {
            // Niente R8: Conscrypt e BouncyCastle usano molta riflessione.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ponte")
        }
        debug {
            signingConfig = signingConfigs.getByName("ponte")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/BCKEY.DSA", "META-INF/BCKEY.SF",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        // Il server scrcpy va letto così com'è dagli asset
        noCompress += "jar"
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha12")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Client ADB (associazione e connessione al Galaxy tramite Debug wireless)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // TLS 1.3 con esportazione delle chiavi, richiesto dal Debug wireless
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Certificato autofirmato per l'identità ADB
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
