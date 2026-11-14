import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: put grab-release.jks in the project folder next to keystore.properties.
// Without them the release build is simply left unsigned.
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreFile = keystore.getProperty("storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "uk.jj.grab"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.jj.grab"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "2.1"
        // Galaxy S23 is arm64. Add "armeabi-v7a" if you ever need 32-bit devices.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystoreFile != null) create("release") {
            storeFile = keystoreFile
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Required by youtubedl-android: native libs must be extracted, not loaded from inside the APK.
    packaging { jniLibs { useLegacyPackaging = true } }

    testOptions { unitTests.isReturnDefaultValues = true }
}

val youtubedlAndroid = "0.18.1"

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    // Built-in player for downloaded files
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // yt-dlp + ffmpeg bundled for Android (GPL-3.0)
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // real JSON parser for unit tests (Android's is a stub off-device)
}
