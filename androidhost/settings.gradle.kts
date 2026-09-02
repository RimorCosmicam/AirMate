pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android is published here and nowhere else.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "AirMateHost"
include(":app")
