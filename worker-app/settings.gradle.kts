pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rwms-worker"

include(
    ":app",
    ":core-auth",
    ":core-network",
    ":core-database",
    ":core-sync",
    ":core-media",
    ":core-ui",
    ":feature-login",
    ":feature-tasks",
    ":feature-task-detail",
    ":feature-camera",
    ":baselineprofile",
)
