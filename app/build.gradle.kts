plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jenziner.nisaba"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jenziner.nisaba"
        minSdk = 34
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"
    }

    // Release signing comes only from environment variables (CI secrets or a local shell).
    // Nothing secret is stored in the repository. Without them, assembleRelease builds an
    // unsigned APK. See docs/RELEASE.md.
    val releaseKeystore = System.getenv("TIINY_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("TIINY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("TIINY_KEY_ALIAS") ?: "tiiny-chat"
            keyPassword = System.getenv("TIINY_KEY_PASSWORD") ?: System.getenv("TIINY_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    // Do not embed dependency metadata for Google Play.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
}
