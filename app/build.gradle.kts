import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    "RWMS_PUBLIC_BASE_URL must be an HTTPS origin without path, query, fragment, or user info"
}

android {
    namespace = "dev.buhanzaz.rwms.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.buhanzaz.rwms.manager"
        minSdk = 23
        targetSdk = 36
        versionCode = 27
        versionName = "0.3.24"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "rwms-manager-auth"
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val cameraVersion = "1.5.0"
    val retrofitVersion = "3.0.0"
    val okHttpVersion = "5.3.2"
    val moshiVersion = "1.15.2"
    // AGP 8.13.2 bundles R8 8.13.19, whose highest supported Kotlin
    // metadata level is 2.3. Kotlin 2.4 requires R8 9.1.29 or newer.
    val kotlinVersion = "2.3.0"

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"))
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.openid:appauth:0.11.1")

    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-moshi:$retrofitVersion")
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("com.squareup.moshi:moshi:$moshiVersion")
    implementation("com.squareup.moshi:moshi-kotlin:$moshiVersion")

    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("androidx.camera:camera-video:$cameraVersion")

    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
        implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okHttpVersion")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
