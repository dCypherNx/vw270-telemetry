import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 4
        versionName = "0.1.3-poc"

        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        buildConfig = true
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // Kept only for CarConnection, which reliably reports projected Android Auto state.
    implementation("androidx.car.app:app:1.7.0")

    implementation("com.google.android.gms:play-services-location:21.4.0")

    // Optional no-root diagnostic layer. Retained as the next fallback after public provider APIs.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
