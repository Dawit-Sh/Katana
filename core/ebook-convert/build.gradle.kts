plugins {
    alias(libs.plugins.yabai.jvm.library)
}

dependencies {
    implementation(libs.jsoup)

    testImplementation(libs.junit4)
}

// `-PebookSamples=<folder>` runs RealBooksConversionTest over the books in that folder (it skips without one).
tasks.withType<Test>().configureEach {
    systemProperty("ebookSamples", providers.gradleProperty("ebookSamples").getOrElse(""))
}
