plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.segeluhr.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.segeluhr.watch"
        // Wear OS 3 (Galaxy Watch 4 und neuer, inkl. Watch 5 Pro) = API 30.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation") // AnimatedVisibility in ui/components/CommandOverlay.kt

    // Wear-spezifisches Compose-Material (RUND-Display-taugliche Komponenten:
    // Chip, ScalingLazyColumn, PositionIndicator, ...) statt Material3 vom
    // Handy-Projekt - andere Bibliothek, gleiche Compose-Laufzeit.
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.wear:wear:1.3.0")

    // Karte für den Wegpunkt-Kartenpicker (siehe docs/Erweiterung_GalaxyWatch_App.md)
    // - dieselbe Bibliothek/Version wie am Handy (WaypointMapPickScreen.kt),
    // kein API-Key nötig. osmdroid ist eine reine View-Bibliothek und läuft
    // über Compose-Interop (AndroidView) unverändert auch unter Wear OS.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
