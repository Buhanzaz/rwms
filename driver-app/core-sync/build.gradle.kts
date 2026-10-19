plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.buhanzaz.rwms.driver.core.sync"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":core-network"))
    implementation(project(":core-database"))
    implementation(project(":core-media"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    ksp(libs.hilt.compiler)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.work.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.retrofit)
    testImplementation(libs.okhttp)
}
