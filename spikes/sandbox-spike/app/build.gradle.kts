plugins {
    id("com.android.application")
}

android {
    namespace = "app.absentmotion.spike"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.absentmotion.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Extract libraries to disk so libpayload.so exists as a real file (test N0/N2).
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.mozilla:rhino:1.7.15")
    // Resolved version is printed by the CI job and put in the release notes.
    implementation("io.github.dokar3:quickjs-kt:+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
