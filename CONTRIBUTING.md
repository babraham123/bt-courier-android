# Contributing

## Build

Prerequisites: JDK 25 and the Android SDK with platform 37. Point Gradle at the SDK with `sdk.dir`
in `local.properties` or the `ANDROID_HOME` environment variable.

Run the full check before opening a pull request; CI runs the same tasks:

```
./gradlew :core:test :fakes:test :app:testDebugUnitTest ktlintCheck :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

## Layout

`core/` is pure Kotlin and must not depend on Android: protocols, sources, clock repair, store,
sinks and formats live there. `fakes/` holds test doubles (fake peripheral, fake WiCAN logger)
and end-to-end JVM tests. `app/` is the Android and Compose layer: BLE link, pairing, workers,
Keystore, settings and UI. Design notes and the task list are in `planning/`; architecture
decisions are recorded as ADRs in `planning/adr/`.

## Code style

- ktlint with the `ktlint_official` style, configured in `.editorconfig`; `./gradlew ktlintCheck`
  must pass, and `./gradlew ktlintFormat` fixes most of what it reports.
- Comments explain why, not what, and describe the code as it is now.

## Tests

- JUnit 5.
- Fakes over mocks; no mocking framework.
- Real protocols against embedded servers (MockWebServer, Apache MINA SSHD, an embedded MQTT
  broker) rather than stubbed clients.
- Tests assert the spec: derive expected values from the protocol or requirement, not from
  current output.

## Dependencies

FOSS-only dependencies are a hard rule: nothing proprietary and no Google Play Services, so the
app can ship on F-Droid. Renovate keeps dependencies updated.

Every configuration is locked in strict mode, so a build fails on any dependency version not in
the committed `*.lockfile`s. After changing a dependency or plugin, run
`./gradlew resolveAndLockAll --write-locks` and commit the updated lockfiles.

## No personal data

Do not put personal data in commits, issues, pull requests, test fixtures or logs: no VINs, trip
or location data, hostnames, IP addresses, account names or credentials. Use synthetic data in
tests. The app's diagnostics export redacts credentials and URL user info; check it before
attaching it to an issue anyway.
