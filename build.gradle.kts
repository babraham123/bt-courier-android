import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// Locks the plugin classpath. Every plugin is declared here, so subprojects add nothing to it.
buildscript {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.test.retry) apply false
}

// Compilers and test JVMs run on the JDK CI installs; settings.gradle.kts provisions it if missing.
val toolchainJdk = 25

allprojects {
    // Every resolvable configuration resolves exactly the versions in its project's gradle.lockfile,
    // so CI builds what was reviewed. After a dependency change, refresh the lockfiles with
    // `./gradlew resolveAndLockAll --write-locks`.
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
    // AGP fills androidApis from the local SDK, never a repository, so it has nothing to lock. It
    // also only exists once a task asks for android.jar, which resolveAndLockAll does not.
    configurations.matching { it.name == "androidApis" }.configureEach {
        resolutionStrategy.deactivateDependencyLocking()
    }
    tasks.register("resolveAndLockAll") {
        description = "Resolves every resolvable configuration, to write lockfiles with --write-locks."
        // Picks the configurations to resolve at execution time, which the configuration cache forbids.
        notCompatibleWithConfigurationCache("Resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "$path needs the --write-locks flag" }
        }
        doLast {
            // The dependency graph is what gets locked; resolving files would need artifact
            // attributes that many Android configurations leave to the task consuming them.
            configurations
                .filter { it.isCanBeResolved }
                .forEach { it.incoming.resolutionResult.root }
        }
    }

    // ktlint for every project, the root included so its own build scripts are checked too. The
    // style (ktlint_official, 120 columns) comes from .editorconfig.
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.engine)
    }
    plugins.withId("com.android.application") {
        extensions.configure<KtlintExtension> {
            android.set(true)
        }
    }
}

subprojects {
    // The toolchain only picks the JDK that compiles and tests; bytecode targets stay at 17. AGP
    // compiles Kotlin itself (built-in Kotlin) and registers the same `kotlin` extension.
    listOf("org.jetbrains.kotlin.jvm", "com.android.application").forEach { pluginId ->
        plugins.withId(pluginId) {
            extensions.configure<KotlinProjectExtension> {
                jvmToolchain(toolchainJdk)
            }
        }
    }

    // Shared setup for the pure-JVM modules (core, fakes). Bytecode targets 17 to match the app
    // module, so core runs unchanged on every Android release the app supports.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        dependencies {
            "testImplementation"(platform(rootProject.libs.junit.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(rootProject.libs.kotlinx.coroutines.test)
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }
    }

    // One test setup for every module: JUnit 5, and only failures in the console, with the full
    // stack trace so a CI log is enough to diagnose them.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }

    // Jar contents must not depend on checkout time or filesystem order (reproducible builds).
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
