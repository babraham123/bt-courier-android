# BT Courier

BT Courier is an Android app that pulls logged data from Bluetooth LE devices, caches it on the
phone, and uploads it in batches to self-hosted backends. The first supported device is the
WiCAN Pro OBD-II dongle, whose HTTP API it speaks over BLE; it catches up from a cursor after a
disconnect, repairs timestamps logged before the device clock was valid, and treats transports,
sources, sinks and formats as plugins. It uses only FOSS dependencies and no Google Play Services.

## Status

Pre-alpha. The core library and the Android app are implemented and unit-tested (277 tests),
but nothing has been verified on real hardware yet. To try it you need:

- a WiCAN Pro on the `dev-v6` firmware with BLE enabled;
- an Android 12+ phone.

Design notes and the task list live in `planning/`.

## Build

Build with JDK 21 and the Android SDK (platform 36), pointing `sdk.dir` in `local.properties` or
`ANDROID_HOME` at the SDK, then run `./gradlew :core:test :fakes:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. The
debug APK lands in `app/build/outputs/apk/debug/`. Licensed under GPL-3.0; see `LICENSE`.
