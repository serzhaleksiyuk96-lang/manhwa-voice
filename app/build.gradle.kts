plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.manhwavoice.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manhwavoice.reader"
        minSdk = 30          // Android 11+: нужен для снимков экрана через спецвозможности
        targetSdk = 34
        // Номер сборки на GitHub — чтобы каждое обновление ставилось поверх старой версии
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Постоянный ключ подписи: без него каждая сборка подписывалась бы новым ключом,
    // и Android не давал бы обновить приложение поверх старого
    signingConfigs {
        create("stable") {
            storeFile = file("manhwa.keystore")
            storePassword = "manhwa123"
            keyAlias = "manhwa"
            keyPassword = "manhwa123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // R8: выкидывает неиспользуемый код (APK меньше) и оптимизирует (быстрее работает)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    buildFeatures {
        compose = true
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

    // Интерфейс: Jetpack Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
