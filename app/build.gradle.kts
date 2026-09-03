import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sidespot"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.sidespot.app"
        minSdk = 30
        targetSdk = 30
        versionCode = 12
        versionName = "0.4.1"

        // Target both 64-bit and 32-bit ARM
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("release-keystore.properties")
            if (propsFile.exists()) {
                val ks = Properties()
                propsFile.inputStream().use { ks.load(it) }
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
    }

    lint {
        // Not distributed via Google Play; suppress Play Store targetSdk requirement
        disable += "ExpiredTargetSdkVersion"
    }

    // The native .so is pre-built by cargo-ndk into jniLibs/
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity + ViewModel
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Serialization (for JSON payloads from JNI)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Image loading (for album art in later phases)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Palette (dynamic theming from album art)
    implementation("androidx.palette:palette:1.0.0")

    // Debug tools
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Task to build the native Rust library before the Android build
tasks.register<Exec>("buildNativeRelease") {
    workingDir = file("${rootProject.projectDir}/native")

    // Add cargo to PATH (common locations)
    val homeDir = System.getProperty("user.home")
    val cargoPath = file("$homeDir/.cargo/bin").absolutePath
    val currentPath = System.getenv("PATH") ?: ""
    environment("PATH", "$cargoPath${if (currentPath.isNotEmpty()) File.pathSeparator else ""}$currentPath")

    doFirst {
        // Use ANDROID_NDK_HOME from environment if set, otherwise try to find it via the android extension
        // Accessing this inside doFirst avoids configuration-phase failures if NDK is missing
        val ndkDir = System.getenv("ANDROID_NDK_HOME") ?: android.ndkDirectory.absolutePath
        if (ndkDir.isNullOrEmpty()) {
            throw GradleException("Android NDK is not installed. Please install NDK version ${android.ndkVersion} via Android Studio SDK Manager.")
        }
        environment("ANDROID_NDK_HOME", ndkDir)
    }

    inputs.dir("${rootProject.projectDir}/native/src")
    inputs.file("${rootProject.projectDir}/native/Cargo.toml")
    outputs.file("${rootProject.projectDir}/app/src/main/jniLibs/armeabi-v7a/libsidespot.so")

    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val cargoExe = if (isWindows) "cargo.exe" else "cargo"
    commandLine(cargoExe, "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-o", "../app/src/main/jniLibs", "build", "--release")
}

// Hook native build into the Android build pipeline
tasks.matching { it.name.startsWith("merge") && (it.name.endsWith("NativeLibs") || it.name.contains("JniLib")) }.configureEach {
    dependsOn("buildNativeRelease")
}
