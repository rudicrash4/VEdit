plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.vedit.editor"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.vedit.editor"
        minSdk = 24
        targetSdk = 36
        // versionCode naik otomatis di CI (nomor run) agar APK baru selalu bisa menimpa yang lama.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("vedit.versionName").get()
    }

    // Signing permanen: hanya aktif jika keystore tersedia lewat environment (CI mengisinya dari GitHub Secrets).
    // Tanpa keystore (build lokal / PR dari fork) Gradle memakai debug key bawaan; APK itu TIDAK bisa
    // dipasang sebagai update di atas APK yang ditandatangani key permanen.
    val keystorePath = System.getenv("VEDIT_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("vedit") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfigs.findByName("vedit")?.let { signingConfig = it }
        }
        release {
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
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    testImplementation(libs.junit)
}
