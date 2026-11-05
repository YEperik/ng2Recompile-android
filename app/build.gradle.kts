plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ng2.dawn"
    compileSdk = 34
    // ReXGlue requires Clang 18+: NDK r27 or newer.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ng2.dawn"
        minSdk = 29            // Vulkan 1.1 devices; scoped storage is the baseline
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.24-android0"

        ndk { abiFilters += listOf("arm64-v8a") }   // 64-bit only: the guest needs the address space

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++23", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON",
                    // Real runtime: pass all three (see docs/ANDROID_PORT.md).
                    "-DNG2_WITH_REXGLUE=${findProperty("ng2.withRexglue") ?: "OFF"}",
                    "-DNG2_REPO_DIR=${findProperty("ng2.repoDir") ?: ""}",
                    "-DREXGLUE_SDK_DIR=${findProperty("ng2.sdkDir") ?: ""}"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.25.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Game data is never packaged: the player supplies it.
    androidResources { noCompress += listOf("cfg") }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
