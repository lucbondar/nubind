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
        // libsu (topjohnwu) se publica en JitPack, no en Maven Central
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "nubind"
include(":app")
