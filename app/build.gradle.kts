plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load API keys from local file (not committed to git)
fun loadApiKey(name: String): String {
    val f = rootProject.file("api_keys.properties")
    if (!f.exists()) return ""
    f.readLines().forEach { line ->
        if (line.startsWith("$name=")) return line.substringAfter("=").trim()
    }
    return ""
}

android {
    namespace = "com.sedu.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sedu.assistant"
        minSdk = 21
        targetSdk = 34
        versionCode = 7
        versionName = "1.4.2"

        // Inject API keys as BuildConfig fields
        buildConfigField("String", "GROQ_API_KEY", "\"${loadApiKey("GROQ_API_KEY")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${loadApiKey("GEMINI_API_KEY")}\"")


        ndk {
            // ARM only — covers 99%+ of real Android phones, halves APK size
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("sedu-release.jks")
            storePassword = "SeduApp2024!"
            keyAlias = "sedu"
            keyPassword = "SeduApp2024!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.google.code.gson:gson:2.10.1")
}
