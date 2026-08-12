import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

if (file("google-services.json").isFile) {
    pluginManager.apply("com.google.gms.google-services")
}

val publicBaseUrl = providers.gradleProperty("RWMS_PUBLIC_BASE_URL")
    .orElse("https://77-90-158-90.sslip.io")
    .get()
    .trim()
    .trimEnd('/')
val parsedPublicBaseUrl = URI(publicBaseUrl)
require(parsedPublicBaseUrl.scheme == "https" && !parsedPublicBaseUrl.host.isNullOrBlank()) {
    "RWMS_PUBLIC_BASE_URL must be an absolute HTTPS public gateway URL"
}
require(
    parsedPublicBaseUrl.userInfo == null &&
        parsedPublicBaseUrl.query == null &&
        parsedPublicBaseUrl.fragment == null &&
        (parsedPublicBaseUrl.path.isNullOrEmpty() || parsedPublicBaseUrl.path == "/"),
) {
    "RWMS_PUBLIC_BASE_URL must be an HTTPS origin, without path, query, fragment, or user info"
}
val signingPropertiesPath = providers.gradleProperty("signingPropertiesFile").orNull
val releaseSigningProperties = signingPropertiesPath?.let { path ->
    Properties().also { properties -> file(path).inputStream().use(properties::load) }
}

android {
    namespace = "dev.buhanzaz.rwms.driver"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.buhanzaz.rwms.driver"
        minSdk = 23
        targetSdk = 36
        versionCode = 16
        versionName = "0.1.15"
        testInstrumentationRunner = "dev.buhanzaz.rwms.driver.HiltDriverTestRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "rwms-driver-auth"
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(project(":core-auth"))
    implementation(project(":core-network"))
    implementation(project(":core-database"))
    implementation(project(":core-sync"))
    implementation(project(":core-media"))
    implementation(project(":core-ui"))
    implementation(project(":feature-login"))
    implementation(project(":feature-tasks"))
    implementation(project(":feature-task-detail"))
    implementation(project(":feature-camera"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.bundles.compose)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.material.adaptive)
    implementation(libs.material.adaptive.layout)
    implementation(libs.material.adaptive.navigation)
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.room.runtime)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.profileinstaller)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.installations)
    implementation(libs.coroutines.android)
    ksp(libs.hilt.compiler)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.hilt.testing)
    kspTest(libs.hilt.compiler)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}
