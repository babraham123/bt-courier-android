import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

// Compilers and test JVMs run on the JDK CI installs; settings.gradle.kts provisions it if missing.
val toolchainJdk = 21

// ktlint for every project, the root included so its own build scripts are checked too. The style
// (ktlint_official, 120 columns) comes from .editorconfig.
allprojects {
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
    // The toolchain only picks the JDK that compiles and tests; bytecode targets stay at 17.
    listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android").forEach { kotlinPlugin ->
        plugins.withId(kotlinPlugin) {
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
