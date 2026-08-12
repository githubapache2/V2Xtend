pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_PROJECT: Kotlin/Native injects an ivy repo for the compiler
    // distribution; FAIL_ON_PROJECT_REPOS / PREFER_SETTINGS break that download.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // usb-serial-for-android
    }
}

rootProject.name = "V2Xtend"

include(":shared")
include(":androidApp")
project(":androidApp").projectDir = file("android/app")
