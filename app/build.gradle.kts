plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------- Rust engine (rust/vedit-bridge)
// Pustaka native dibuild dengan cargo (host untuk unit test JVM, cargo-ndk untuk APK). Tanpa cargo/cargo-ndk
// (mis. build lokal minimal) tugas dilewati dan aplikasi memakai engine Kotlin cadangan. CI memakai
// -Pvedit.requireNative=true sehingga build GAGAL jika pustaka native tidak bisa dibuat.
val rustBridgeDir = rootProject.layout.projectDirectory.dir("rust/vedit-bridge").asFile
val rustJniDir = layout.buildDirectory.dir("rustJniLibs").get().asFile
val requireNative = providers.gradleProperty("vedit.requireNative").orNull == "true"

fun commandWorks(vararg cmd: String): Boolean = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    p.inputStream.readBytes()
    p.waitFor() == 0
} catch (e: Exception) {
    false
}

val cargoOk = commandWorks("cargo", "--version")
val cargoNdkOk = cargoOk && commandWorks("cargo", "ndk", "--version")

val buildRustHost = tasks.register<Exec>("buildRustHost") {
    group = "rust"
    description = "Build libvedit_bridge untuk host (dipakai unit test JVM: smoke test + differential test)."
    workingDir = rustBridgeDir
    commandLine("cargo", "build", "--release")
    onlyIf { cargoOk }
    doFirst { if (requireNative && !cargoOk) throw GradleException("vedit.requireNative=true tetapi cargo tidak tersedia") }
}

val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
    group = "rust"
    description = "Cross-compile libvedit_bridge untuk arm64-v8a dan armeabi-v7a via cargo-ndk."
    workingDir = rustBridgeDir
    commandLine("cargo", "ndk", "--platform", "24", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-o", rustJniDir.absolutePath, "build", "--release")
    outputs.dir(rustJniDir)
    onlyIf { cargoNdkOk }
    doFirst { if (requireNative && !cargoNdkOk) throw GradleException("vedit.requireNative=true tetapi cargo-ndk tidak tersedia") }
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
    sourceSets.getByName("main").jniLibs.srcDir(rustJniDir)
}

// AGP menjatuhkan builtBy dari srcDir, jadi dependensi dipasang eksplisit pada tugas penggabung jniLibs.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRustAndroid)
}

tasks.withType<Test>().configureEach {
    dependsOn(buildRustHost)
    jvmArgs("-Djava.library.path=${rustBridgeDir.resolve("target/release").absolutePath}")
    if (requireNative) environment("VEDIT_REQUIRE_NATIVE", "1")
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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
