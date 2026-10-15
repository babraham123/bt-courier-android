plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.test.retry)
}

tasks.test {
    // Every fork is a JVM of its own running embedded servers; half the cores leaves room for them.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // Covers the network-bound embedded-server suites (Apache MINA SSHD, Moquette, MockWebServer),
    // whose loopback sockets and timeouts can flake on a loaded machine. Moquette in particular
    // cannot bind port 0, so it takes a free port first and can lose it to another fork.
    retry {
        maxRetries.set(2)
        maxFailures.set(5)
        failOnPassedAfterRetry.set(false)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sshj)
    implementation(libs.hivemq.mqtt.client)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.moquette.broker)
}
