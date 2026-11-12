plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Exporta el esquema de Room (JSON por versión) a neopen/schemas. Compile-time: no cambia el
// runtime. Habilita validar/testear migraciones a futuro y deja constancia del esquema en git.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "net.micapum.neopen.app"
    compileSdk = 34

    defaultConfig {
        // App unificada (puente + completa). El :companion quedó retirado del build.
        applicationId = "net.micapum.neopen"
        minSdk = 26
        targetSdk = 34
        versionCode = 40
        versionName = "0.25.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // Upload key de producción para Google Play. NO se commitea el keystore:
        // se inyecta por variables de entorno (en local y en CI vía GitHub Secrets).
        // Si las variables no están presentes, la firma de release no se configura
        // (las builds debug/CI siguen funcionando sin tocar nada).
        val ksPath = System.getenv("NEOPEN_KEYSTORE_PATH")
        if (!providers.gradleProperty("fdroid").isPresent && ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("NEOPEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NEOPEN_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("NEOPEN_KEY_PASSWORD")
                    ?: System.getenv("NEOPEN_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Firma con la upload key solo si está configurada (env presente).
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    testOptions.unitTests.isIncludeAndroidResources = true
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

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    // Carga de portadas remotas (Coil)
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
