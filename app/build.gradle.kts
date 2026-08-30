plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "space.schoolcommunity.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "space.schoolcommunity.app"
        minSdk = 26
        targetSdk = 34
        // SINGLE SOURCE OF TRUTH for app version. tools/release.sh bumps these,
        // then publishes the matching metadata to the Mobile Release Registry.
        versionCode = 2
        versionName = "1.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log etc. no-op instead of throwing
    }

    // Release signing from env vars (see README "Release signing"). Falls back to the
    // debug keystore when SC_KEYSTORE is unset so local `assembleRelease` still works.
    signingConfigs {
        create("release") {
            val ks = System.getenv("SC_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SC_KEYSTORE_PASS")
                keyAlias = System.getenv("SC_KEY_ALIAS")
                keyPassword = System.getenv("SC_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            signingConfig =
                if (System.getenv("SC_KEYSTORE") != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0") // pull-to-refresh
    // WebViewCompat.addDocumentStartJavaScript — inject the download shim before page scripts run.
    implementation("androidx.webkit:webkit:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (android.jar stubs it)
}
