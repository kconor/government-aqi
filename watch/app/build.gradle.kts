import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }
val prodAqiBaseUrl = "https://aqi-worker.kevin-61f.workers.dev/"
val debugAqiBaseUrl = localProps.getProperty("aqi.base.url.debug") ?: prodAqiBaseUrl

android {
    namespace = "com.example.aqi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aqi"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "AQI_BASE_URL", "\"$prodAqiBaseUrl\"")
        buildConfigField("String", "API_SECRET", "\"${localProps.getProperty("api.secret") ?: ""}\"")
    }

    buildTypes {
        debug {
            // On physical watches, 10.0.2.2 is unreachable; default debug to production unless overridden.
            buildConfigField("String", "AQI_BASE_URL", "\"$debugAqiBaseUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("androidx.compose.ui:ui:1.6.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.1")
    implementation("androidx.wear.compose:compose-material:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // WorkManager and Coroutines for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Complications
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // OkHttp / Gson for network
    implementation("com.squareup.okhttp3:okhttp:3.14.9")
    implementation("com.google.code.gson:gson:2.8.5")
}
