import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.building"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.building"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0-eap"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes.addAll(
            listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ide-core"))
    implementation(project(":agent-api"))
    implementation(project(":agent-impl"))
    implementation(project(":vcs-api"))
    implementation(project(":vcs-impl"))
    implementation(project(":deps-api"))
    implementation(project(":deps-impl"))
    implementation(project(":store-api"))
    implementation(project(":store-impl"))
    implementation(project(":analytics-api"))
    implementation(project(":analytics-impl"))
    implementation(project(":developer-workbench"))
    implementation(project(":agent-ui-foundation"))
    implementation(project(":agent-ui-compose"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
