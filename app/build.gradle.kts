plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.micapum.neopen.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.micapum.neopen.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.1"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Clave de firma fija y versionada: garantiza que todas las builds (local y
        // CI) se firmen igual, de modo que las actualizaciones se instalen sin tener
        // que desinstalar la versión anterior.
        getByName("debug") {
            storeFile = file("signing/neopen.keystore")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Persistencia de ajustes
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Trabajo en segundo plano (sincronización por intervalo)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Red: cliente del servidor neopen (contrato docs/openapi.yaml)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
