import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// android-apk: open a .apk on a host with no Android runtime and execute its Dalvik bytecode.
//
// The pieces are the ones every APK host has to solve (apkenv's linker, Husk's DVM, Mac.apk's dex path):
// a ZIP reader for the package, a DEX reader, an interpreter over Dalvik registers, and host stubs for the
// handful of android.* / java.* types a real Activity touches before it paints. The interpreter is the
// guest CPU; everything else is the translation layer.
//
// Multiplatform so the same code is tested on the JVM (with a recorded DEX fixture) and linked into the
// iOS app behind `:ide-ios`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosSimulatorArm64(), iosArm64()).forEach { /* host bindings land here when native .so support arrives */ }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kotlin-classfile"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
