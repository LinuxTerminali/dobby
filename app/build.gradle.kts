plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.dobby"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dobby"
        minSdk = 31
        targetSdk = 35
        // CI sets GITHUB_RUN_NUMBER so every build on main gets a higher versionCode (in-place updates).
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2.${System.getenv("GITHUB_RUN_NUMBER") ?: "dev"}"
        // AppAuth redirect scheme for Google OAuth (Android client type, no secret)
        manifestPlaceholders["appAuthRedirectScheme"] = "app.dobby"
    }
    signingConfigs {
        // Fixed debug key so sideloaded builds upgrade in place instead of needing an uninstall.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Local LLM runtime. Pin to a tested version after the first green build.
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // JVM unit tests (real org.json instead of the Android stub)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // OAuth (PKCE) for Google connectors
    implementation("net.openid:appauth:0.11.1")
}
