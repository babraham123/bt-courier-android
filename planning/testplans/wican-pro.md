# Hardware test plan: WiCAN Pro

Settles H1–H6 and R1 in `planning/tasks.md`, plus the "Unverified on hardware/a device" notes on
A1–A6. Protocol facts: wican-fw `dev-v6` `HTTP_API.md` §6e6 (logger export) and
`ble_http/BLE_HTTP_PROTOCOL.md`. Run in order; each case starts where the previous one ended.
Never commit captures: they hold VINs, trips and addresses.

## Preconditions

- [ ] WiCAN Pro on `dev-v6` (H1); note the build from the web UI.
- [ ] Device settings: BLE on, `pairing_at_boot`, default passkey; data logger on, params `format` = jsonl, `autopid_log` = changed or all.
- [ ] Web UI reachable over Wi-Fi for `/api/logger`, `/api/rtc`, `/api/sleep`, `/api/fs` (cross-checks only).
- [ ] Android 12+ phone (H2 names a Pixel 7); Bluetooth on; car head unit paired to the phone.
- [ ] Debug APK for cases 1–8 and 10, release APK for case 9.
- [ ] An HTTPS PUT sink you control: logs method, path, status, size; supports HEAD; objects downloadable.
- [ ] `adb logcat` for `CarTrigger`, `PresenceService`, `Worker` lines.

**Capture** = Status → Save log (diagnostics export: app log + store listing), plus the named
screen text or log line. App log lines look like `<instant> I sync: sync WICAN …`.

## 1. Pair, bond, MITM

- [ ] Pair → WiCAN. CDM chooser lists `WiC_<12 hex>` (name is in the scan response).
- [ ] The system dialog asks for a **passkey** (not a bare "Pair?" confirm); enter the device's.
- [ ] Bond completes; wizard moves to Test.
- [ ] Toggle phone Bluetooth off/on, power-cycle the dongle: still listed as bonded (rotating address resolved).
- Capture: dialog type, `pairing: paired; presence observed: true`.
- Settles: H2 scan-response name, passkey dialog, bonded rotating address; A2 bond receiver. A Just Works bond would fail case 2: the tunnel is served only on an encrypted MITM link.
- If it fails: `WicanGatt.kt` (filter; fallback FFF0 alone), `CompanionPairing.kt`, `PairingScreen.kt`.

## 2. Test step: tunnel and MTU

- [ ] Test connection. Expect `Firmware <version>, MTU 517`.
- [ ] Lower MTU: note it; frames shrink to `min(490, MTU-3) - 8`, still works.
- [ ] Repeat after a fresh post-bond reconnect (case 1 toggle).
- Capture: Test-step text verbatim.
- Settles: H2 MTU 517, FFF3/FFF4 after a fresh post-bond connection; A1 CCCD/notify, writes.
- If it fails: `KableGattLink.kt`, `GattProfile.kt`, `WicanSupport.kt`, `BleHttpTransport.kt`.

## 3. First sync: export paging and truncation

- [ ] Log 2+ drives (or 20 min idling) across several files. Note `/api/logger` `files`, `file_rows`, `written`.
- [ ] Sync now. Expect `sync WICAN CAUGHT_UP (CAUGHT_UP): N pages, M lines, 0 duplicates, cursor <epoch>:<off>`.
- [ ] N ≈ M / 500 (limit 500, 64 KB per response); M ≈ rows written plus one `_file` marker per file.
- [ ] Store listing: a batch per file, each starting `{"_file":"dl_<epoch>.jsonl"}`.
- [ ] No loop at a file end: cursor moves past every file (the offset-0 workaround, L7 report).
- [ ] Throughput: bytes pulled ÷ sync duration ≈ 60 KB/s. Device `blehttp` counters: no `timeouts`, counter gaps or `stalls`.
- [ ] Any `INTERRUPTED (TRUNCATED)`: note the page; the retry must resume at the cursor.
- [ ] Sync now again: `0 duplicates`, cursor unchanged or advanced only by new rows.
- [ ] H6: cross-check one drive's HV_A/HV_W sign and cumulative kWh/Ah against Car Scanner over the dongle's ELM327 BLE.
- Capture: sync lines, `/api/logger` before/after, device `blehttp` counters.
- Settles: H2 credit pacing and throughput; W1/W2 paging, cursor format, export end-of-file bug (L7); H3 (record frames into `fakes/` now).
- If it fails: `WicanLoggerSource.kt`, `ExportPageParser.kt`, `ResponseAssembler.kt`, `BleHttpTransport.kt`.

## 4. Cursor catch-up after disconnect

- [ ] Phone Bluetooth off, drive, back on: Sync now catches up the whole trip.
- [ ] Mid-sync with a large backlog, walk out of range.
- [ ] Expect `INTERRUPTED (LINK_LOST)`, a retry with back-off (max 5 runs), resume at the cursor; no row twice across uploaded batches.
- Capture: sync lines, cursors.
- Settles: plan goal "catch up after a disconnect", A3 retry, B4 link release.
- If it fails: `SyncJob.kt`, `SyncRunner.kt`, `KableGattLink.kt`.

## 5. Clock journal and `ts_quality`

- [ ] Dongle with no Wi-Fi and no network time: cold boot (unplug, replug), log 10 min.
- [ ] Then give it network time (Wi-Fi with SNTP); `/api/logger` `clock.valid` true, `steps_this_boot` ≥ 1.
- [ ] Sync, upload, download the batch.
- [ ] Rows logged before the set, in files the journal lists as open: `ts_quality":"journal"`, `ts` = `ts_raw` + `delta_ms`.
- [ ] Rows after the set: `device`. Implausible rows the journal does not cover: `anchor` (re-based by the sync's `/api/rtc` anchor) or `unknown` with `ts: null`.
- [ ] Cross-check: download `/sd/logs/clock.jsonl` and the file via `/api/fs`, run upstream `tools/log_clock.py` with `python3 -I`; `ts` must match.
- Capture: `/api/logger` `clock{}`, the journal's event types (`boot`/`valid`/`step`/`rotate`), count of rows per `ts_quality`.
- Settles: H5; W3/W4 against real journals.
- If it fails: `ClockJournalParser.kt`, `JournalNormalizer.kt`, `WicanLoggerSource.kt` (`/api/rtc`).

## 6. Upload and ack

- [ ] Add the HTTPS PUT sink (Jsonl, gzip, Basic). On Wi-Fi: Upload now.
- [ ] Expect `upload DELIVERED [<sink id> DELIVERED], 0 batches pending`; one `<sha256>.jsonl.gz` per batch, 2xx.
- [ ] Sink answers 503 + `Retry-After: 120`: upload line says `next attempt in 120 s`, no PUT before then. Answers 403: `FAILED`, batches stay pending.
- [ ] Cellular toggle off on mobile data: upload waits for Wi-Fi.
- Capture: sink access log, upload lines.
- Settles: A4, T5 on a device.
- If it fails: `HttpPutUploadTransport.kt`, `HttpUploadResult.kt`, `Delivery.kt`, `UploadJob.kt`.

## 7. Triggers: presence, car, periodic

- [ ] Presence: app closed, screen off, walk to the car, ignition on. `PresenceService` in logcat, a sync line within a minute.
- [ ] Car: Settings → car head unit. Connect: sync. Disconnect at trip end: sync inside the ~3 min awake window. Two events within 60 s: one sync.
- [ ] Periodic: 2 h with the car parked. Dongle asleep: `UNREACHABLE … device unreachable: …`, batches still sealed by age and uploaded.
- Capture: logcat `PresenceService`/`CarTrigger`, sync lines with times.
- Settles: A5, A6 on a device.
- If it fails: `DevicePresence.kt`, `CarTrigger.kt`, `SyncTriggers.kt`, `Scheduler.kt`.

## 8. Parked 24 h: sleep, phantom trips

- [ ] Park 24 h; measure pin-16 current awake and asleep; poll `/api/sleep` when awake.
- [ ] Count short sessions where the car's Aux Battery Saver+ woke the dongle: each a short `_file` with a few rows. Expected, not an app bug; the receiver splits sessions by gap.
- [ ] Known external issue: wican-fw #718 (sleep). If the dongle never sleeps or never wakes, record it there, do not change the app.
- Capture: current readings, wake count, `/api/sleep` `state`/`naps`.
- Settles: H4.
- If it fails: none in the app; `SyncJob.kt` only if unreachable runs misbehave.

## 9. Release APK on the device (R8)

- [ ] Uninstall debug, install the release APK, re-pair, sync, upload to the PUT sink.
- [ ] Add an MQTT sink (TLS broker you control) and an SFTP sink (server you control, trust host key on first use, Keystore key): Upload now.
- [ ] Expect `DELIVERED` per sink; message on the broker, `.jsonl.gz` on the server, no `.part` left.
- [ ] `adb logcat | grep -E 'ClassNotFound|NoSuchMethod|NoSuchAlgorithm|InvalidKey|BC'`: empty. A `BC` provider error = Android's built-in `BC` shadowing sshj's bundled one.
- Capture: upload lines, logcat matches.
- Settles: R1 "shrunk APK's MQTT and SFTP paths", the `BC` provider risk.
- If it fails: `proguard-rules.pro`, `SftpUploadTransport.kt`, `ProviderNeutralEcdsa.kt`, `MqttUploadTransport.kt`.

## 10. Drive session

Needs case 3 to show that `/api/logger/export` serves rows from the file the logger is still
writing (ticket 0001's prerequisite); otherwise rounds store nothing until the dongle rotates the
file, and the lever is the rotation setting, not this feature.

- [ ] Settings → Keep syncing while driving on, pull every 2 min, idle 10 min, longest 3 h; car head unit set.
- [ ] App closed, screen off: get in, connect the head unit. Within a minute a "Drive session" notification with Stop; log `drive session WICAN <address> started (PULL)`.
- [ ] Drive 30 min. Status screen: "Drive session: N rounds, M rows, since HH:MM" with N ≈ 15; the sink receives at least 10 batches, each within 2 min + upload delay + upload time of its rows being logged.
- [ ] Sync now during the session: no second connection (no `sync WICAN` line with a link), one extra round at once (`handed to the running drive session`).
- [ ] Periodic syncs during the session end at once (the session holds the lock) and presence enqueues nothing: no second link (logcat `PresenceService`, no extra connection in the device's `blehttp` counters).
- [ ] Walk out of range for a minute and back: `link lost …; reopening`, then rounds continue; out of range for longer: session ends `LINK_LOST`.
- [ ] Park, engine off, head unit disconnects: one last round within a minute of the disconnect, then `ended CAR_DISCONNECTED`; the notification disappears.
- [ ] Engine on, parked, nothing logged (car off with the head unit connected, if possible): `ended IDLE` 10 min after the last stored row.
- [ ] Notification Stop: `ended USER_STOPPED` at once (after a round in progress).
- [ ] Toggle off: car connect enqueues one sync, as in case 7.
- [ ] Battery: Battery Historian over a 1-hour session; compare with a 1-hour A2DP connection.
- [ ] Kill the app process mid-session (`adb shell am kill`): the service restarts and resumes the session (new start time), or note that it did not.
- Capture: `drive session` log lines with times, status screen text, sink access log, Battery Historian summary.
- Settles: ticket 0001 acceptance; background FGS start from the car trigger and presence on this phone.
- If it fails: `DriveSession.kt`, `DriveSessionService.kt`, `SyncTriggers.kt` (`planTrigger`), `CarTrigger.kt`, `DevicePresence.kt`.
