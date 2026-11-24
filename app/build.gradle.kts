plugins {
    alias(libs.plugins.yabai.android.application)
    alias(libs.plugins.yabai.android.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.unifiedreader"

    defaultConfig {
        applicationId = "com.example.unifiedreader"
        targetSdk = 35
        versionCode = 77
        versionName = "1.3.1"
        vectorDrawables.useSupportLibrary = true
    }

    val releaseSigningStoreFile = providers.environmentVariable("ANDROID_SIGNING_STORE_FILE").orNull
    val releaseSigningStorePassword = providers.environmentVariable("ANDROID_SIGNING_STORE_PASSWORD").orNull
    val releaseSigningKeyAlias = providers.environmentVariable("ANDROID_SIGNING_KEY_ALIAS").orNull
    val releaseSigningKeyPassword = providers.environmentVariable("ANDROID_SIGNING_KEY_PASSWORD").orNull
    val releaseSigningConfig = if (
        !releaseSigningStoreFile.isNullOrBlank() &&
        !releaseSigningStorePassword.isNullOrBlank() &&
        !releaseSigningKeyAlias.isNullOrBlank() &&
        !releaseSigningKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("release") {
            storeFile = file(releaseSigningStoreFile)
            storePassword = releaseSigningStorePassword
            keyAlias = releaseSigningKeyAlias
            keyPassword = releaseSigningKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            // R8: much less code for the Fire 7 to load and check at every start. Keep rules in
            // proguard-rules.pro; the Baseline Profile is in src/main/baselineProfiles.
            isMinifyEnabled = true
            isShrinkResources = true
            // Local Fire 7 (2019) build: without the release keys, sign with this PC's debug key
            // so the APK installs; the release stays a fast (non-debuggable) build.
            signingConfig = releaseSigningConfig ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // One release build makes three APKs (2026-11-19), all with the same versionCode, signature and
    // Baseline Profile, so a device can move between them: armeabi-v7a only for the Fire 7 (2019, 32-bit
    // Android), arm64-v8a only for modern phones, and a universal one (armeabi-v7a, arm64-v8a, x86_64)
    // for anyone unsure. Translation's native code is ~12-17 MB per ABI, which is why the first two
    // carry one ABI each. `katanaReleaseApks` below gives them their release names. A build that only
    // asks for debug tasks keeps the single app-debug.apk with every ABI, as before.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { task -> !task.contains("Debug") }
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    sourceSets {
        getByName("test").java.setSrcDirs(listOf("src/test/java", "src/sharedTest/java"))
        getByName("androidTest").java.setSrcDirs(listOf("src/androidTest/java", "src/sharedTest/java"))
    }
}

// The release APKs under their published names, in app/build/outputs/katana-apks:
// katana-<version>-fire7-armeabi-v7a.apk, katana-<version>-arm64-v8a.apk, katana-<version>-universal.apk.
// The in-app updater picks among them by these names (feature:updates, ReleaseApkChoice).
val katanaReleaseApks = tasks.register<Sync>("katanaReleaseApks") {
    description = "Copies the release APKs to build/outputs/katana-apks under their release names."
    into(layout.buildDirectory.dir("outputs/katana-apks"))
}
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // No 32-bit x86 devices run Android 9+ in practice; leaving x86 out keeps ~17 MB of
        // translation code out of the universal APK.
        variant.packaging.jniLibs.excludes.add("lib/x86/**")
        val version = android.defaultConfig.versionName
        val releaseNames = mapOf(
            "app-armeabi-v7a-release.apk" to "katana-$version-fire7-armeabi-v7a.apk",
            "app-arm64-v8a-release.apk" to "katana-$version-arm64-v8a.apk",
            "app-universal-release.apk" to "katana-$version-universal.apk",
        )
        katanaReleaseApks.configure {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)) {
                include(releaseNames.keys)
                rename { apk -> releaseNames.getValue(apk) }
            }
        }
    }
}
tasks.named { name -> name == "assembleRelease" }.configureEach { dependsOn(katanaReleaseApks) }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui-theme"))
    implementation(project(":core:ui-components"))
    implementation(project(":core:model"))
    implementation(project(":core:ebook-convert"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":data:kavita"))
    implementation(project(":data:stump"))
    implementation(project(":data:grimmory"))
    implementation(project(":data:repository"))
    implementation(project(":feature:library"))
    implementation(project(":feature:library-covers"))
    implementation(project(":feature:library-shelves"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:reader-preferences"))
    implementation(project(":feature:reader-core"))
    implementation(project(":feature:reader-comics"))
    implementation(project(":feature:reader-pdf"))
    implementation(project(":feature:reader-epub"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:journal"))
    implementation(project(":feature:lists"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:updates"))
    implementation(project(":feature:translation"))
    implementation(project(":feature:annotations"))
    implementation(project(":feature:dictionary"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // Puts the Baseline Profile in place on the device so start-up code is compiled ahead of time.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.junrar)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.activity.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    ksp(libs.hilt.compiler)
}
