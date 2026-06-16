plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App slim "neopen Live": solo conexión al boli + teclado de escritura en vivo (ML Kit).
// Convive con la app oficial (:neopen) en el mismo dispositivo (applicationId distinto).
android {
    namespace = "net.micapum.neopen.live"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.micapum.neopen.live"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
        // Solo arm64-v8a: el nativo de ML Kit (libdigitalink.so) pesa ~11 MB/ABI; con un
        // único ABI el APK baja drásticamente. (No instala en armeabi-v7a viejos ni x86.)
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Reutiliza el keystore (ya versionado) de :neopen. Al tener applicationId distinto
        // (.live), ambas apps conviven aunque compartan clave de firma. Evita commitear una
        // credencial nueva.
        create("app") {
            storeFile = rootProject.file("neopen/signing/neopen-full.keystore")
            storePassword = "neopenpen"
            keyAlias = "neopen"
            keyPassword = "neopenpen"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            // R8 + resource shrink: poda el dex (ML Kit/Compose) → APK ligero.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("app")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint {
        // Falso positivo: usamos ComponentActivity (activity-compose), no Fragments, así que
        // registerForActivityResult es válido sin la dependencia de Fragment.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    // Núcleo compartido: BLE/boli, protocolo v2, ajustes. (Sin ML Kit: vive aquí.)
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Reconocimiento de escritura a mano on-device (online HWR, offline).
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    // Task de ML Kit ↔ corrutinas (.await())
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
