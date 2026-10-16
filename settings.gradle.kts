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

// Locks the settings plugin classpath. Its default lockfile, settings-gradle.lockfile, also holds
// the lock state Gradle keeps for importing gradle/libs.versions.toml, and Gradle writes that state
// separately, replacing the classpath entries; so the classpath gets a file of its own. Renovate
// refreshes every *.lockfile by running `dependencies --write-locks`, which resolves this classpath.
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
