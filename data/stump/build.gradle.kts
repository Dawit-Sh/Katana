plugins {
    alias(libs.plugins.yabai.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    // Its API hands out core:network types (PartialDownloadFile).
    api(project(":core:network"))
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
}
