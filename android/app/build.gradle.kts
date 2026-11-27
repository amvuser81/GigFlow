plugins {
    id("com.android.application")
}

// Release signing is supplied by CI (see .github/workflows/build-apk.yml).
// Without GIGFLOW_KEYSTORE the release variant builds unsigned, which is fine
// for local experiments but must never be published.
val releaseKeystore = System.getenv("GIGFLOW_KEYSTORE")

android {
    namespace = "app.gigflow.driver"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gigflow.driver"
        minSdk = 26
        targetSdk = 36
        versionCode = 10728
        versionName = "1.7.28"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("GIGFLOW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GIGFLOW_KEY_ALIAS")
                keyPassword = System.getenv("GIGFLOW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // FileProvider for handing the downloaded update APK to the installer.
    implementation("androidx.core:core:1.13.1")

    // Test-only. The parser is plain Kotlin (see Bounds in models.kt) so its
    // decisions can be replayed offline against captured node dumps.
    testImplementation("junit:junit:4.13.2")
}
