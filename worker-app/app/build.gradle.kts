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

android {
    namespace = "dev.buhanzaz.rwms.worker"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.buhanzaz.rwms.worker"
        minSdk = 23
        targetSdk = 36
        versionCode = 47
        versionName = "0.1.46"
        testInstrumentationRunner = "dev.buhanzaz.rwms.worker.HiltWorkerTestRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "rwms-worker-auth"
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
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
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    ksp(libs.hilt.compiler)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
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
