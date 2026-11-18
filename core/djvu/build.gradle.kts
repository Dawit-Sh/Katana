plugins {
    alias(libs.plugins.yabai.android.library)
}

android {
    namespace = "com.example.unifiedreader.core.djvu"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    testImplementation(libs.junit4)
}
