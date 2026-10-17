import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The screens: the project home, the store, Learn and the daily challenges, settings, the run and logs
// views, the SDK and icon managers. A screen composes components and the editor into one destination; the
// shell (:ide-ui) is what routes between them.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val cupertinoRoot = file("/Users/mac/.building/composeui-bridge/compose-cupertino")
val composeUiRoot = file("/Users/mac/.building/composeui-bridge/composeui/src")
val cupertinoModules = listOf(
    "cupertino-core",
    "cupertino",
    "cupertino-native",
    "cupertino-adaptive",
    "cupertino-decompose",
    "cupertino-icons-extended",
)

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    android {
        namespace = "dev.ide.ui.screens"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // iOS. The screens are common Compose over the `IdeBackend` port, so they build for iOS unchanged.
    iosSimulatorArm64()
    iosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":ide-ui-core"))
            api(project(":ide-ui-components"))
            api(project(":ide-ui-editor"))
            implementation(libs.kotlinx.serialization.json)
        }

        val cupertinoCommon = create("cupertinoCommon") {
            dependsOn(getByName("commonMain"))
            cupertinoModules.forEach { mod ->
                kotlin.srcDir(cupertinoRoot.resolve("$mod/src/commonMain/kotlin"))
            }
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                implementation("org.jetbrains.kotlinx:atomicfu:0.23.2")
                implementation("com.arkivanov.decompose:decompose:3.0.0")
                implementation("com.arkivanov.decompose:extensions-compose:3.0.0")
                implementation(compose.materialIconsExtended)
                implementation(compose.uiUtil)
            }
        }

        val jvmShared = create("jvmShared") {
            dependsOn(cupertinoCommon)
            cupertinoModules.forEach { mod ->
                kotlin.srcDir(cupertinoRoot.resolve("$mod/src/nonIosMain/kotlin"))
                kotlin.srcDir(cupertinoRoot.resolve("$mod/src/jvmMain/kotlin"))
            }
            kotlin.srcDir(composeUiRoot.resolve("commonMain/kotlin"))
        }

        val desktopMain by getting {
            dependsOn(jvmShared)
            cupertinoModules.forEach { mod ->
                kotlin.srcDir(cupertinoRoot.resolve("$mod/src/skikoMain/kotlin"))
                kotlin.srcDir(cupertinoRoot.resolve("$mod/src/desktopMain/kotlin"))
            }
            kotlin.srcDir(composeUiRoot.resolve("desktopMain/kotlin"))
            resources.srcDir(composeUiRoot.resolve("desktopMain/resources"))
        }

        val androidMain by getting {
            dependsOn(jvmShared)
            cupertinoModules.forEach { mod ->
                kotlin.srcDir(cupertinoRoot.resolve("$mod/src/androidMain/kotlin"))
            }
            kotlin.srcDir(composeUiRoot.resolve("androidMain/kotlin"))
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation("androidx.media3:media3-exoplayer:1.4.1")
                implementation("androidx.media3:media3-ui:1.4.1")
            }
        }

        val iosMain = create("iosMain") { dependsOn(getByName("commonMain")) }
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        getByName("iosArm64Main").dependsOn(iosMain)

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":ide-ui-testing"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
