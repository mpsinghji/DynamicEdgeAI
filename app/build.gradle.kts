import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dynamicedgeai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dynamicedgeai"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_KEY", "\"AIzaSyCF7VizsgjqQQG3SFSE3CDw1hzMueFvvsM\"")
        
        ndk {
            // Filter for common architectures to reduce APK size
            // llamacpp-kotlin only supports arm64-v8a
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // This ensures native libraries are extracted correctly
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // MediaPipe GenAI & Core — updated to 0.10.35 for LiteRT-LM (.litertlm), MPImage, and Gemma 4 support
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("com.google.mediapipe:tasks-core:0.10.35")

    // llamacpp-kotlin (Maven Central) — real llama.cpp bindings for GGUF models
    // Supports DeepSeek-R1, TinyLlama, and any other arm64-v8a GGUF model
    implementation("io.github.ljcamargo:llamacpp-kotlin:0.2.0")
}
