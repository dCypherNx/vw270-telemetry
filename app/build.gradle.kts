plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.jurgensen.vw270telemetry"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.jurgensen.vw270telemetry"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-poc"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // Optional no-root diagnostic layer. Requires the Shizuku app/service on the phone.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
