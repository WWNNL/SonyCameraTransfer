plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.rea.sonytransfer"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.rea.sonytransfer"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "0.10.1"
    }

    buildTypes {
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
