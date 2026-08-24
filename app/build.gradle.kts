plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.mauriciogamedev.overlay01"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mauriciogamedev.overlay01"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
}
