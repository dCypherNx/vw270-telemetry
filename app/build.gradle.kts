import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val legacyAautoSdkAar = configurations.create("legacyAautoSdkAar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies.add("legacyAautoSdkAar", "com.github.martoreto:aauto-sdk:v4.7@aar")

val legacyAautoSdkJar = layout.buildDirectory.file("legacy-aauto/aauto-sdk-v4.7-classes.jar")
val extractLegacyAautoSdk by tasks.registering {
    inputs.files(legacyAautoSdkAar)
    outputs.file(legacyAautoSdkJar)
    doLast {
        val aar = legacyAautoSdkAar.singleFile
        val classesJar = zipTree(aar).matching { include("classes.jar") }.singleFile
        val output = legacyAautoSdkJar.get().asFile
        output.parentFile.mkdirs()
        classesJar.copyTo(output, overwrite = true)
    }
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

    // The legacy SDK AAR contains resource syntax that modern AAPT2 rejects. We intentionally
    // consume only classes.jar: the ExLAP PoC needs the vendor-extension Java API, not its UI.
    implementation(files(legacyAautoSdkJar))

    // Optional no-root diagnostic layer. Retained until the ExLAP path is proven on-device.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

tasks.named("preBuild").configure {
    dependsOn(extractLegacyAautoSdk)
}
