pluginManagement {
    repositories {
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
    // The version catalog is not available to settings plugins, so its pinned version is read
    // straight from the file; the catalog stays the one place Renovate updates.
    val foojayVersion =
        file("gradle/libs.versions.toml")
            .readLines()
            .firstNotNullOf { Regex("""^foojay-resolver\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version foojayVersion
    }
}

// Locks the settings plugin classpath. Its default lockfile name is the one the root project's
// buildscript writes too, so it gets a name of its own.
buildscript {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
        lockFile.set(file("settings-buildscript-gradle.lockfile"))
    }
}

// Downloads the toolchain JDK the build files ask for when no local install matches.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bt-courier"

include(":core", ":fakes", ":app")
