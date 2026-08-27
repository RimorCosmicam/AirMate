plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
