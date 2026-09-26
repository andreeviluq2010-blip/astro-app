plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.astrohyperlapse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.astrohyperlapse"
        minSdk = 31
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0-ProWizard"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3 -ffast-math -fopenmp"
                arguments += listOf("-DANDROID_STL=c++_shared")
                val opencvDir = System.getenv("OPENCV_ANDROID_SDK")
                if (!opencvDir.isNullOrEmpty()) {
                    arguments += "-DOpenCV_DIR=${opencvDir}/sdk/native/jni"
                }
            }
        }

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
