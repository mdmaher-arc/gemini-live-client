plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── Release signing credentials ──────────────────────────────────────────────
// Never hardcode signing passwords in version control. Supply them via CI
// secrets (environment variables) or locally through the git-ignored
// local.properties / -P Gradle properties:
//     KEYSTORE_PASSWORD=...   KEY_PASSWORD=...   KEY_ALIAS=...
val releaseKeystoreFile = file("release.keystore")
val releaseStorePassword =
    (project.findProperty("KEYSTORE_PASSWORD") as String?) ?: System.getenv("KEYSTORE_PASSWORD")
val releaseKeyPassword =
    ((project.findProperty("KEY_PASSWORD") as String?) ?: System.getenv("KEY_PASSWORD"))
        ?: releaseStorePassword
val releaseKeyAlias =
    (project.findProperty("KEY_ALIAS") as String?) ?: System.getenv("KEY_ALIAS") ?: "gemini-live-key"
val hasReleaseSigning =
    releaseKeystoreFile.exists() && releaseStorePassword != null && releaseKeyPassword != null

android {
    namespace = "com.geminilive.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geminilive.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // No keystore or credentials available — fall back to debug
                // signing so local release builds still succeed.
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
}
