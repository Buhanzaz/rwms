plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.buhanzaz.rwms.driver.core.database"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.hilt.android)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    ksp(libs.room.compiler)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
}
