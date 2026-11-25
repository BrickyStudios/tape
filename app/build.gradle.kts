plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.brickystudios.tape"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.brickystudios.tape"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.9.0"
        buildConfigField("String", "APK_NAME", "\"Tape.apk\"")
    }

    // Release key lives only in CI secrets (and ~/.config/tape on the maintainer's machine).
    // Without it, local release builds fall back to the debug key so they still install.
    val storePass = System.getenv("SIGNING_STORE_PASSWORD")
    signingConfigs {
        if (storePass != null) create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "release.jks")
            storePassword = storePass
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // no Google dependency info block: F-Droid/GrapheneOS users can check the APK has nothing to hide
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-core:1.7.8") // back arrow and search glass; the BOM stopped carrying icons
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in local unit tests
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
