plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val airMateSigningStore = System.getenv("AIRMATE_KEYSTORE_PATH")

android {
    namespace = "com.airmate.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airmate.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (airMateSigningStore != null) {
            create("airMateTest") {
                storeFile = file(airMateSigningStore)
                storePassword = System.getenv("AIRMATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AIRMATE_KEY_ALIAS")
                keyPassword = System.getenv("AIRMATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (airMateSigningStore != null) {
                signingConfig = signingConfigs.getByName("airMateTest")
            }
        }
    }

    buildFeatures { buildConfig = true; compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // The code scanner lives in an optional Play services module. An app installed from Play gets
    // it at install time from the manifest meta-data; a sideloaded build never does, so it has to
    // be requested explicitly at runtime.
    implementation("com.google.android.gms:play-services-base:18.5.0")

    testImplementation("junit:junit:4.13.2")
}
