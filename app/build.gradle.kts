plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.mauriciogamedev.overlay01"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mauriciogamedev.overlay01"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
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
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.0")
}
