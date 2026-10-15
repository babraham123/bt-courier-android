import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Shared setup for the pure-JVM modules (core, fakes). Bytecode targets 17 to match the app
// module, so core runs unchanged on every Android release the app supports.
subprojects {
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
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    // Jar contents must not depend on checkout time or filesystem order (reproducible builds).
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
