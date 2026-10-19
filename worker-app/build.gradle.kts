plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Compose BOM 2026.06.00 brings a newer lifecycle atomic group transitively.
// Lifecycle 2.11's Android artifacts require API 37 and AGP 9.1, whereas this
// private APK is intentionally pinned to AGP 9.0.1 / compileSdk 36. Keep the
// entire atomic group on the newest compatible 2.10 line rather than allowing
// a mixed set of lifecycle artifacts.
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.lifecycle") {
                useVersion("2.10.0")
                because("worker-app is frozen on AGP 9.0.1 and compileSdk 36")
            }
        }
    }
}
