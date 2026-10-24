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

rootProject.name = "my-audio-library"

include(":core")
// وحدة :app تحتاج Android SDK. للعمل على المنطق فقط دون SDK: ./gradlew :core:test -PcoreOnly
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
