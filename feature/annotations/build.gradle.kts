plugins {
    alias(libs.plugins.yabai.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.unifiedreader.feature.annotations"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
