plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":agent-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
