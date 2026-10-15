# BT Courier

BT Courier is a FOSS Android app that extracts logged BLE device data, caches it locally, and batch-uploads to self-hosted backends without Google Play Services. It initially supports the WiCAN Pro OBD-II dongle via HTTP-over-BLE, featuring cursor-based disconnect recovery, early-timestamp repair, and a plugin architecture for transports, sources, sinks, and formats.

## Status

Pre-alpha. The core library and app are implemented, unit-tested, and CI-checked on every push, but unverified on hardware. To try it, you need:

- a WiCAN Pro on `dev-v6` firmware with BLE enabled
- an Android 12+ phone

Planned distribution is via GitHub Releases (for Obtainium), then F-Droid. See `CHANGELOG.md` for updates and `planning/` for design notes and tasks.

## Build

Requires JDK 21 and the Android SDK (platform 36) configured via `sdk.dir` in `local.properties` or `ANDROID_HOME`. Run full checks:

```
./gradlew :core:test :fakes:test :app:testDebugUnitTest ktlintCheck :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

The debug APK outputs to `app/build/outputs/apk/debug/`, the unsigned, R8-shrunk, reproducible release APK to `app/build/outputs/apk/release/`. See `CONTRIBUTING.md` for code style, tests, and the no-personal-data rule, and `SECURITY.md` to report vulnerabilities.

## Attribution

- The WiCAN Pro protocol follows MeatPi's [wican-fw](https://github.com/meatpiHQ/wican-fw) firmware and its HTTP-over-BLE specification.
- The Colmi smart ring protocol follows tahnok's [colmi_r02_client](https://github.com/tahnok/colmi_r02_client) and Gadgetbridge's Colmi R0x support.
- BLE access uses [Kable](https://github.com/JuulLabs/kable); SFTP and MQTT sinks use [sshj](https://github.com/hierynomus/sshj) and the [HiveMQ MQTT client](https://github.com/hivemq/hivemq-mqtt-client).

## License

GPL-3.0; see `LICENSE`.
