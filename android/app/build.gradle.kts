plugins {
    id("com.android.application")
}

android {
    namespace = "app.gigflow.driver"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gigflow.driver"
        minSdk = 26
        targetSdk = 36
        versionCode = 10714
        versionName = "1.7.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
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
}

