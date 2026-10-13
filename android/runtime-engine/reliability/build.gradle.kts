// SPDX-License-Identifier: GPL-3.0-or-later
// Optional typed bridge; neither the core engine nor generated apps depend on it by default.
plugins { id("com.android.library") }
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.ide.runtime.engine.reliability"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    api(project(":runtime-engine"))
    implementation(project(":applog-runtime"))
    testImplementation("junit:junit:4.13.2")
}
