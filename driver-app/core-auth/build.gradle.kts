plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.buhanzaz.rwms.driver.core.auth"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        // AppAuth declares a generic redirect receiver in its library manifest.
        // Native login never launches it and the application removes it from
        // the final manifest. This inert value only keeps library/unit-test
        // manifest merging well-formed.
        manifestPlaceholders["appAuthRedirectScheme"] = "rwms-driver-auth"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.appauth)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
}
