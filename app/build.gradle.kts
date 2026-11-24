plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.garitto.exoduspanel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.garitto.exoduspanel"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
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
}

dependencies {
    // Solo Kotlin stdlib; el resto es API del sistema (Bluetooth, notificaciones, etc.)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
