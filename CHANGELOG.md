<!-- The release workflow publishes the "## [x.y.z]" section whose version matches the pushed tag; keep one such section per release, newest first. -->
# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- About screen, opened from Settings: version, source code and bug report links, license and
  credits.

### Changed

- Clearer wording in Settings and on the status screen: "upload target" replaces "sink", drive
  sessions "sync every" rather than "pull every", and settings that apply to every device no
  longer mention the dongle.

## [0.0.2] - 2026-10-16

### Added

- Drive session, off by default and unverified on hardware: with "Keep syncing while driving"
  on, the car head unit connecting or the WiCAN coming in range starts a foreground service that
  holds the BLE link and pulls every 1 to 10 minutes (2 by default) until the car disconnects
  (after one last pull), no new data has arrived for the idle time (10 minutes by default), the
  session reaches its maximum length (3 hours by default), the link is lost and cannot be
  reopened, or Stop is pressed in its notification. A manual sync during a session pulls on the
  open link. The status screen and the notification show the session's rounds, rows and start
  time. Sessions also collect live-streaming sources such as OBD-II (ELM327) adapters.
- ELM327 OBD-II adapters over BLE as a live source, collected by the drive session: polls a car's wican-fw vehicle profile
  (82 cars plus a generic mode-01 set) and writes `autopid.*` rows named as the WiCAN logger names
  them, plus adapter version and battery voltage.
- Pairing for ELM327 BLE adapters: "OBD-II scanner (ELM327)" in the pair wizard with a searchable
  vehicle profile picker; the adapter's serial service is discovered on its first connection and
  remembered; the connection test shows the adapter version and battery voltage.

## [0.0.1] - 2026-10-16

Pre-alpha. Nothing below has been verified on real hardware.

### Added

- WiCAN Pro source: speaks the dongle's HTTP API over BLE (`ble_http`), pulls the data logger
  from a persisted cursor and resumes after a disconnect.
- Timestamp repair for rows logged before the device clock was valid, using the device's clock
  journal and a per-sync anchor; each row carries a `ts_quality`.
- Colmi smart ring source, experimental: per-day heart rate, HRV, steps, sleep, SpO2 and
  temperature logs, battery, firmware revision and the background heart-rate setting, which the
  pair wizard offers to turn on. The current day is pulled hourly during the day and completed
  after midnight.
- Local cache with batch sealing, gzip and a byte cap with oldest-first eviction.
- Upload delay setting (after each sync by default, or 5 or 30 minutes); data a sync leaves in
  an open batch gets an upload scheduled for when that batch's delay ends, not the next sync.
- Sinks: HTTP PUT, HTTP POST, SFTP, MQTT and a local folder (Storage Access Framework); several
  sinks at once, each acknowledged independently.
- Formats: JSONL, CSV, Prometheus text and raw.
- Auth: Basic, Bearer and OIDC device authorization flow; credentials and the SFTP key are held
  in the Android Keystore.
- Triggers: device presence (CompanionDeviceManager), periodic sync, car head unit Bluetooth
  connect/disconnect, and manual sync/upload. Automatic syncs leave a ring alone for an hour
  after its last pull; a manual sync always connects.
- Pair wizard, status screen, settings with a sink editor, and a redacted log export.
