plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.edith.rokid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.edith.rokid"
        // Rokid Glasses本体はAndroid 12(SDK 32)。minSdk 28はRokidAIAssistant実績に合わせた値。
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // クライアント側顔検出（edith_mobileのMediaPipe tasks-visionと同じモデルを使う）
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Face API / Voice Agent への通信（edith_mobileのfaceApi.ts/voiceAgentApi.tsと同じAPI契約）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.json:json:20240303")
}
