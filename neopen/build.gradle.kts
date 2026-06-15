plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "net.micapum.neopen.app"
    compileSdk = 34

    defaultConfig {
        // App unificada (puente + completa). El :companion quedó retirado del build.
        applicationId = "net.micapum.neopen"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.14.3"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Clave fija propia de :neopen (distinta del companion) para que las
        // actualizaciones se instalen sin desinstalar la versión anterior.
        getByName("debug") {
            storeFile = file("signing/neopen-full.keystore")
            storePassword = "neopenpen"
            keyAlias = "neopen"
            keyPassword = "neopenpen"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // Núcleo compartido con el companion (BLE/boli, API, ajustes, Uploader)
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Trabajo en segundo plano (motor de sincronización — Fase 2/3)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Widgets de pantalla de inicio (sincronización + cuadernos)
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    // Persistencia local offline (cuadernos/capturas + outbox + cursor)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Carga de portadas remotas (Coil)
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
