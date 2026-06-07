plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marathoncoach"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marathoncoach"
        minSdk = 28  // Health Connect requires API 28+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Injected from gradle.properties or GitHub secrets
        buildConfigField(
            "String", "GOOGLE_CLIENT_ID",
            "\"${project.findProperty("GOOGLE_CLIENT_ID") ?: ""}\""
        )
        buildConfigField(
            "String", "GOOGLE_CLIENT_SECRET",
            "\"${project.findProperty("GOOGLE_CLIENT_SECRET") ?: ""}\""
        )
        // Name of the folder that will be created in your Google Drive
        buildConfigField("String", "DRIVE_FOLDER_NAME", "\"RunningCoach\"")

        // OAuth uses localhost:8765 redirect — no manifest placeholder needed
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signing is handled by GitHub Actions using the keystore secret
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
        }
    }

    signingConfigs {
        // Release signing config is injected by CI — see .github/workflows/build.yml
        // For local debug builds, the default debug keystore is used automatically
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
    // Health Connect — official Android health data API
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // WorkManager — runs the sync job in the background on a schedule
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp — makes Google Drive REST API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Chrome Custom Tabs — keeps app alive during OAuth so localhost server isn't killed
    implementation("androidx.browser:browser:1.8.0")

    // UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
