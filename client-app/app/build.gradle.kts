import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val publicBaseUrl = providers.gradleProperty("RWMS_PUBLIC_BASE_URL")
    .orElse("https://77-90-158-90.sslip.io")
    .get()
    .trim()
    .trimEnd('/')
val publicOrigin = URI(publicBaseUrl)
require(publicOrigin.scheme == "https" && !publicOrigin.host.isNullOrBlank()) {
    "RWMS_PUBLIC_BASE_URL must be an absolute HTTPS public gateway origin"
}
require(
    publicOrigin.userInfo == null &&
        publicOrigin.query == null &&
        publicOrigin.fragment == null &&
        (publicOrigin.path.isNullOrEmpty() || publicOrigin.path == "/"),
) {
    "RWMS_PUBLIC_BASE_URL must not contain a path, query, fragment, or user info"
}
val signingPropertiesPath = providers.gradleProperty("signingPropertiesFile").orNull
val releaseSigningProperties = signingPropertiesPath?.let { path ->
    Properties().also { properties -> file(path).inputStream().use(properties::load) }
}
val mapkitPropertiesPath = providers.gradleProperty("mapkitPropertiesFile")
    .orElse("/var/lib/rwms-secrets/customer-app/mapkit.properties")
    .get()
val mapkitPropertiesFile = file(mapkitPropertiesPath)
require(mapkitPropertiesFile.isFile) {
    "Yandex MapKit configuration is missing; pass -PmapkitPropertiesFile=<protected-properties-file>"
}
val mapkitApiKey = Properties().also { properties ->
    mapkitPropertiesFile.inputStream().use(properties::load)
}.getProperty("mapkitApiKey")?.trim().orEmpty()
require(mapkitApiKey.isNotEmpty()) {
    "The protected MapKit properties file must define a non-empty mapkitApiKey"
}

android {
    namespace = "dev.buhanzaz.rwms.client"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.buhanzaz.rwms.client"
        minSdk = 30
        targetSdk = 36
        versionCode = 13
        versionName = "0.1.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MAPKIT_API_KEY", "\"$mapkitApiKey\"")
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "PUBLIC_BASE_URL", "\"$publicBaseUrl\"")
            ndk {
                abiFilters += setOf("x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "PUBLIC_BASE_URL", "\"$publicBaseUrl\"")
            if (releaseSigningProperties != null) {
                signingConfig = signingConfigs.create("externalRelease") {
                    storeFile = file(requireNotNull(releaseSigningProperties.getProperty("storeFile")))
                    storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword"))
                    keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias"))
                    keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword"))
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.datastore)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.yandex.mapkit)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    ksp(libs.hilt.compiler)
    coreLibraryDesugaring(libs.desugar)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
