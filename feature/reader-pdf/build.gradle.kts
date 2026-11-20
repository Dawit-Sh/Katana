plugins {
    alias(libs.plugins.yabai.android.library)
    alias(libs.plugins.yabai.android.compose)
}

android {
    namespace = "com.example.unifiedreader.feature.reader.pdf"
}

dependencies {
    implementation(project(":core:ui-components"))
    implementation(project(":core:model"))
    implementation(project(":core:djvu"))
    implementation(project(":feature:reader-core"))
    implementation(project(":feature:annotations"))
    implementation(project(":feature:dictionary"))
    implementation(project(":feature:reader-preferences"))
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // PDFium, only for the text of a PDF page: selecting words and placing highlights. PdfRenderer still draws.
    implementation(libs.pdfium.android)

    testImplementation(libs.junit4)
}
