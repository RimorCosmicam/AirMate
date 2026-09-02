plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val hostSigningStore = System.getenv("AIRMATE_KEYSTORE_PATH")

android {
    namespace = "com.airmate.host"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airmate.host"
        // The pairing flow needs mDNS discovery of the wireless debugging port, and gestures have
        // to name the display they land on. Both arrived in 30.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hostSigningStore != null) {
            create("airMateTest") {
                storeFile = file(hostSigningStore)
                storePassword = System.getenv("AIRMATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AIRMATE_KEY_ALIAS")
                keyPassword = System.getenv("AIRMATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (hostSigningStore != null) signingConfig = signingConfigs.getByName("airMateTest")
        }
        release {
            // The host jar inside this APK is launched by class name from a shell process, so no
            // part of it may be renamed or stripped for being apparently unreachable.
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            // The shell-side host is compiled into the APK rather than pushed to the device.
            // `app_process` is given this APK as its CLASSPATH, which means there is one artifact
            // to install, one to update, and no writable copy of our own code sitting in
            // /data/local/tmp for anything else on the device to read or replace.
            java.srcDir("../server/src")
        }
    }

    buildFeatures { buildConfig = true; compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The ADB client: SPAKE2 pairing, the TLS handshake and the shell streams.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Pairing exports TLS keying material through Conscrypt's public API. The platform's own
    // Conscrypt hides a differently shaped method on some releases, which shows up as a
    // NoSuchMethodException in the middle of the handshake.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    testImplementation("junit:junit:4.13.2")
}
