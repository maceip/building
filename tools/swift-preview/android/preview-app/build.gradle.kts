plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
android {
    namespace = "dev.ide.swiftpreview.demo"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.building.swiftpreview"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libbuilding-preview.so" } }
}
dependencies {
    implementation(project(":preview"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
}
