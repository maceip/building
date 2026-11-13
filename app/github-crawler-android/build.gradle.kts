// A standalone background crawler for GitHub Trending and the developer/repository graph.
//
// This is the phone-side counterpart of tools/github-crawler (the macOS daemon). It is a
// deliberately separate, tiny application: a background indexer should not drag the whole IDE
// (kotlinc, JDT, Compose UI) into memory just to keep a SQLite index warm for weeks.
//
// The index schema, the graph model and the rate-limit policy are identical to the macOS daemon,
// so the two databases can be merged later.
//
// Kotlin is built into com.android.application under AGP 9 (no separate kotlin-android plugin).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.ide.crawler"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ide.crawler"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1"
        testApplicationId = "dev.ide.crawler.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // The host-side live test exercises the real HTTP client, which logs
    // through android.util.Log. Without this, Log calls throw in unit tests.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // AGP's unit-test runner is JUnit4 by default.
    testImplementation(libs.junit4)

    // On-device tests: the live trending page and one real crawl cycle.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
