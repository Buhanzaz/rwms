import java.net.URI
import java.util.Properties
import org.gradle.api.GradleException

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
val releaseSigningPropertyNames =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val signingPropertiesPath =
    providers.gradleProperty("signingPropertiesFile").orNull?.trim()?.takeIf(String::isNotEmpty)
val signingPropertiesFile = signingPropertiesPath?.let(::file)
val releaseSigningProperties =
    signingPropertiesFile
        ?.takeIf { it.isFile && it.canRead() }
        ?.let { propertiesFile ->
            runCatching {
                Properties().also { properties ->
                    propertiesFile.inputStream().use(properties::load)
                }
            }.getOrNull()
        }
val releaseSigningStoreFile =
    releaseSigningProperties
        ?.getProperty("storeFile")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::file)
val releaseSigningConfigurationReady =
    releaseSigningProperties != null &&
        releaseSigningPropertyNames.all { propertyName ->
            !releaseSigningProperties.getProperty(propertyName).isNullOrBlank()
        } &&
        releaseSigningStoreFile?.isFile == true &&
        releaseSigningStoreFile?.canRead() == true

fun requireExternalReleaseSigning() {
    val configuredPath =
        signingPropertiesPath
            ?: throw GradleException(
                "Release APK/bundle tasks require -PsigningPropertiesFile=<protected-properties-file>.",
            )
    val propertiesFile = file(configuredPath)
    if (!propertiesFile.isFile || !propertiesFile.canRead()) {
        throw GradleException(
            "Release signing properties file is missing or unreadable: $configuredPath",
        )
    }
    val properties =
        try {
            Properties().also { loaded ->
                propertiesFile.inputStream().use(loaded::load)
            }
        } catch (exception: Exception) {
            throw GradleException(
                "Release signing properties file could not be read: $configuredPath",
                exception,
            )
        }
    val missingProperties =
        releaseSigningPropertyNames.filter { propertyName ->
            properties.getProperty(propertyName).isNullOrBlank()
        }
    if (missingProperties.isNotEmpty()) {
        throw GradleException(
            "Release signing properties file is incomplete; missing non-empty keys: " +
                missingProperties.joinToString(", "),
        )
    }
    val keystorePath = properties.getProperty("storeFile").trim()
    val keystoreFile = file(keystorePath)
    if (!keystoreFile.isFile || !keystoreFile.canRead()) {
        throw GradleException("Release signing keystore is missing or unreadable: $keystorePath")
    }
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
        versionCode = 25
        versionName = "0.1.24"
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
            if (releaseSigningConfigurationReady) {
                val properties = requireNotNull(releaseSigningProperties)
                signingConfig = signingConfigs.create("externalRelease") {
                    storeFile = releaseSigningStoreFile
                    storePassword = properties.getProperty("storePassword")
                    keyAlias = properties.getProperty("keyAlias")
                    keyPassword = properties.getProperty("keyPassword")
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

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails closed unless external release signing is complete and readable"
    doLast {
        requireExternalReleaseSigning()
    }
}

val releaseArtifactTaskNames =
    setOf(
        "assembleRelease",
        "bundleRelease",
        "packageRelease",
        "packageReleaseBundle",
        "signReleaseBundle",
    )
tasks.configureEach {
    if (name in releaseArtifactTaskNames) {
        dependsOn(validateReleaseSigning)
    }
}
val releaseArtifactProjectPath = project.path
gradle.taskGraph.whenReady(
    org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
        if (
            allTasks.any { task ->
                task.project.path == releaseArtifactProjectPath && task.name in releaseArtifactTaskNames
            }
        ) {
            requireExternalReleaseSigning()
        }
    },
)

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
    implementation(libs.androidx.work)
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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
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
