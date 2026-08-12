plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "dev.buhanzaz.rwms.driver.baselineprofile"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 23
        targetSdk = 36
    }
}
