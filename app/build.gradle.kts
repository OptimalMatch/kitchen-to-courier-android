plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ie.unidatum.courier"
    compileSdk = 35
    defaultConfig {
        applicationId = "ie.unidatum.courier"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        // The node, DuckDB and musl's loader are executables shipped as lib*.so:
        // Android will run an app's own native-library files, and nowhere
        // else the app can write. They must be extracted to disk (not paged
        // from the APK) to be exec'd, and must not be stripped.
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    // The map: OpenStreetMap tiles through osmdroid. No API key and no Play Services,
    // which keeps the app installable on any arm64 phone — the same reason the node is bundled.
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
