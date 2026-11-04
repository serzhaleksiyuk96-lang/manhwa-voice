plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manhwavoice.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manhwavoice.reader"
        minSdk = 30          // Android 11+: нужен для снимков экрана через спецвозможности
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    androidResources {
        noCompress += "traineddata"
    }
}

dependencies {
    // Tesseract OCR (многопоточный вариант) — умеет распознавать русский текст
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
