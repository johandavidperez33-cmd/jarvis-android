plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número de versión: lo pone GitHub en cada fabricación (sube solo: 10, 11, 12…)
val numeroVersion = (System.getenv("NUMERO_VERSION") ?: "3").toInt()

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26
        targetSdk = 34
        versionCode = numeroVersion
        versionName = "3.$numeroVersion"
    }

    // Firma fija: así cada versión nueva se instala encima de la anterior (actualización)
    val archivoFirma = System.getenv("FIRMA_ARCHIVO")
    signingConfigs {
        if (archivoFirma != null) {
            create("fija") {
                storeFile = file(archivoFirma)
                storePassword = System.getenv("FIRMA_CLAVE")
                keyAlias = "jarvis"
                keyPassword = System.getenv("FIRMA_CLAVE")
                storeType = "jks"
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            if (archivoFirma != null) signingConfig = signingConfigs.getByName("fija")
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Vosk: reconoce la palabra "Jarvis" dentro del celular, sin internet y sin cuentas
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
