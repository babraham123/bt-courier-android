import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.btcourier"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.btcourier"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // VCS info embeds the commit hash and dirty state, which would differ between an
            // F-Droid rebuild and ours.
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    // F-Droid rejects the Google-encrypted dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Library code is not ours to fix; lint still analyses what the app calls into it.
        checkDependencies = false
        // Holds findings that cannot be fixed; absent while there are none.
        baseline = file("lint-baseline.xml")
        // Path-scoped suppressions that keep each check on for our own code.
        lintConfig = file("lint.xml")
    }

    testOptions {
        // Robolectric screen tests resolve strings, plurals and themes from the merged resources.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            // Library build metadata: unused at runtime and a source of non-reproducible bytes.
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module",
                    "/META-INF/versions/**",
                    "/META-INF/INDEX.LIST",
                    "/META-INF/DEPENDENCIES",
                    // Every netty jar (pulled in by the MQTT client) ships its own copy.
                    "/META-INF/io.netty.versions.properties",
                    "/META-INF/LICENSE*",
                    "/META-INF/NOTICE*",
                    "/kotlin-tooling-metadata.json",
                    "DebugProbesKt.bin",
                )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Kotlin's debugImplementation metadata configuration does not inherit implementation, so it
    // needs the BOM for these versionless artifacts; without it Renovate's lockfile run fails.
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kable.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    testImplementation(project(":fakes"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Robolectric's SDK 36 sandbox sets file descriptors through this JDK-internal API.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}
