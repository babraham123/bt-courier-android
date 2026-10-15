<!-- The release workflow publishes the "## [x.y.z]" section whose version matches the pushed tag; keep one such section per release, newest first. -->
# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Pre-alpha. Nothing below has been verified on real hardware.

### Added

- WiCAN Pro source: speaks the dongle's HTTP API over BLE (`ble_http`), pulls the data logger
  from a persisted cursor and resumes after a disconnect.
- Timestamp repair for rows logged before the device clock was valid, using the device's clock
  journal and a per-sync anchor; each row carries a `ts_quality`.
- Colmi smart ring source (heart rate, steps, battery), experimental.
- Local cache with batch sealing, gzip and a byte cap with oldest-first eviction.
- Sinks: HTTP PUT, HTTP POST, SFTP, MQTT and a local folder (Storage Access Framework); several
  sinks at once, each acknowledged independently.
- Formats: JSONL, CSV, Prometheus text and raw.
- Auth: Basic, Bearer and OIDC device authorization flow; credentials and the SFTP key are held
  in the Android Keystore.
- Triggers: device presence (CompanionDeviceManager), periodic sync, car head unit Bluetooth
  connect/disconnect, and manual sync/upload.
- Pair wizard, status screen, settings with a sink editor, and a redacted log export.
