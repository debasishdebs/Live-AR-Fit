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
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "live-fitness-tracker"

// Apps
include(":phone", ":watch", ":glasses")

// Shared contracts
include(":core:model", ":core:services", ":core:map")

// One module per service; each ships a Fake now and gains a Live implementation later.
include(
    ":services:workout",
    ":services:glasses-link",
    ":services:watch-link",
    ":services:music",
    ":services:voice",
    ":services:voice-android",
    ":services:sync",
    ":services:confirm",
    ":services:history",
)
