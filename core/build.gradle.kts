plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.micapum.neopen.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Red: cliente del servidor neopen (contrato docs/openapi.yaml).
    // Se exponen como `api` para que :companion y :neopen los hereden.
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.retrofit2:converter-moshi:2.11.0")
    api("com.squareup.moshi:moshi-kotlin:1.15.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // Persistencia de ajustes (compartida por ambas apps)
    api("androidx.datastore:datastore-preferences:1.1.1")

    // Corrutinas / Flow (usadas por PenManager, SettingsStore, SyncStatusStore)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Reconocimiento de escritura a mano on-device (modo escritura en vivo).
    // Online HWR: toma los trazos vectoriales del boli (Ink) → texto, offline.
    api("com.google.mlkit:digital-ink-recognition:18.1.0")
    // Tareas de ML Kit (Task) ↔ corrutinas
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
