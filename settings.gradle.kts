// Dependency resolution.
//
// By default this resolves from Google Maven and Maven Central, which is correct and canonical.
//
// If those are slow or unreachable (common on mainland China networks, where a full Compose + Room
// build pulls several hundred megabytes of artefacts), re-run Gradle with:
//
//     gradle <task> -PuseCnMirrors
//
// and the Aliyun mirrors are prepended. They are *prepended*, not substituted: if a mirror is missing
// an artefact, Gradle still falls through to the upstream repository, so enabling this cannot break a
// build that would otherwise have succeeded — it only changes which host is tried first.

val useCnMirrors = providers.gradleProperty("useCnMirrors").isPresent

pluginManagement {
    val useCn = providers.gradleProperty("useCnMirrors").isPresent
    repositories {
        if (useCn) {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useCnMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "HotspotAccounting"
include(":app")
