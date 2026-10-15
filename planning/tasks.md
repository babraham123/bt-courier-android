# Tasks

Status 2026-10-14: every software task is done (291 unit tests across core,
fakes and app; lint clean; release build reproducible). Open items need the
hardware (section 5), a signing key (section 6) or come later (section 7).

Status: `todo` | `doing` | `blocked` | `done`. Deps are task ids.

## 0. Setup

| id | task | status | deps |
|---|---|---|---|
| S1 | Name: BT Courier; package id `app.btcourier` | done | — |
| S2 | Gradle multi-module skeleton: `core/` (pure Kotlin), `app/` (Android), `fakes/`; min API 31; `dependenciesInfo` off; pinned AGP/Gradle | done | S1 |
| S3 | LICENSE (GPL-3.0), README, CI (build + `core` tests) | done | S2 |
| S4 | Reproducible-build check (two clean builds, identical APK): two clean copies at different paths, `--no-build-cache`, give byte-identical `app-release-unsigned.apk` and `core.jar`, with or without NDK 27.0.12077973 (AGP 8.13's default) installed; `libandroidx.graphics.path.so` ships pre-stripped, so `stripReleaseDebugSymbols` leaves it unchanged. Not yet tested: a different JDK or a Linux host | done | S2 |

## 1. Core: ble_http

| id | task | status | deps |
|---|---|---|---|
| B1 | Frame codec: request header, DATA/CREDIT/END frames, frame counter, payload size `min(490, MTU-3) - 8` | done | S2 |
| B2 | Test vectors from `BLE_HTTP_PROTOCOL.md` §6 byte examples | done | B1 |
| B3 | Reassembler with credit window (16 KB), credit every ≤8 KB, 30 s timeout, lost-PDU detection (§7 pseudocode) | done | B1 |
| B4 | `Transport` interface + `BleHttp` implementation over an abstract GATT link (no Android deps); response idle timeout (40 s, not while uploading) → `ResponseTimeout` + ABORT; dropped or cancelled `stream()` bodies release the link | done | B3 |
| B5 | `WifiHttp` transport (OkHttp) implementing the same interface; `Auth` (`None`/`Basic`/`Bearer` over a `TokenSource`) | done | B4 |
| B6 | Fake peripheral in `fakes/` replaying recorded frames, incl. link loss and 429 | done | B3 |

## 2. Core: WiCAN source + clock repair

| id | task | status | deps |
|---|---|---|---|
| W1 | Export parser: JSONL rows, `{"_file":...}` markers, trailing `{"_cursor","more"}` | done | S2 |
| W2 | `Source` interface, `WicanLoggerSource` (export pages, journal, `/api/rtc` device clock, typed `SourceException`s, workaround for the export's offset-0 cursor at an exact file end) + `SyncRunner` (status → journal → pull until `more:false` → seal; journal-first, see its KDoc) | done | W1, B4 |
| W3 | Clock journal parser (`boot`/`valid`/`step`/`rotate` events) | done | S2 |
| W4 | Normalizer: per-file re-basing by journal `delta_ms`; per-sync anchor; `ts_quality` | done | W1, W3 |
| W5 | Tests asserting `log_clock.py` semantics on synthetic journals and files | done | W4 |

## 3. Core: store + sinks

| id | task | status | deps |
|---|---|---|---|
| T1 | Store interface + SQLite schema: pages keyed by cursor, batches, per-sink acks, counters | done | S2 |
| T2 | Batch sealing (file boundary / 1 MB / 30 min) + gzip | done | T1 |
| T3 | Byte cap with oldest-first eviction and dropped counter | done | T1 |
| T4 | `Sink = Transport × Format` interfaces; `Jsonl`, `Raw` formats | done | T2 |
| T5 | `HttpPut` sink with Basic auth, gzip, sha256-named objects, retry/backoff (`Retry-After`; 409/412 acked after a HEAD length match). 3xx, or any response reached through a redirect (OkHttp replays a redirected PUT as a GET), is `Failed`, never an ack; the app's client also has redirects off | done | T4 |
| T6 | `LocalFile` sink (SAF) | todo | T4 |
| T7 | End-to-end JVM test: fake peripheral → source → normalizer → store → local sink (`fakes` `EndToEndSyncTest`, `FakeWicanLogger`) | done | B6, W4 |

## 4. Android app

| id | task | status | deps |
|---|---|---|---|
| A1 | GATT link adapter (`KableGattLink`, Kable 0.42): connect by bonded address, `requestMtu(517)`, connection priority High, FFF3 notify (CCCD `0x0001`) into an unbounded buffer that completes on link loss, FFF4 writes with/without response, idempotent `close()`, `LinkState` flow. Unverified on hardware (H2) | done | B4 |
| A2 | Pair wizard: `CompanionDeviceManager` association (FFF0 + `WiC_<12 hex>`), `createBond()` with the system passkey dialog, test connection (`GET /api/status` version); the CDM association is the paired-device record (`CompanionPairedDeviceRepository`). The bond-state receiver is `RECEIVER_EXPORTED`: the Bluetooth stack sends from its own uid, so a non-exported receiver never hears it. Unverified on hardware (H2) | done | A1 |
| A3 | Sync worker: `SyncWorker` runs `SyncJob` (address via `PairedAddress`, link via `LinkFactory` → `BleHttpTransport` → `WicanLoggerSource` → `SyncRunner` → store; store source id `wican:<address>`, so a replacement dongle gets its own cursor); `setForeground(connectedDevice)` only once the link is open, falling back to background if the system refuses; a process-wide lock keeps periodic and one-time syncs from overlapping; retry with back-off on link lost/truncated/busy (max 5 runs), success otherwise; enqueues an upload whenever the run sealed new batches (`SyncReport.sealed`, not a pending-count delta, which eviction and a concurrent upload offset); unreachable/not-paired runs still seal by age so a short trip's rows go out while the dongle sleeps. Seams wired in `AppGraph` (manual DI, set up by `BtCourierApp`). Unverified on a device | done | A2, W2, T1 |
| A4 | Upload worker: `UploadWorker` runs `UploadJob` (seals by the current policy first, then `deliverPending` per sink, sinks independent); `UNMETERED`, `CONNECTED` with the cellular toggle; expedited, promoted to `dataSync` FGS when ≥ 8 MB is stored (uncompressed; ~1 min of a slow uplink after gzip); `Retry` → WorkManager exponential back-off from 30 s, capped at 8 rounds (~1 h) then `GAVE_UP`; `Failed` never retried; both leave batches pending and show in `SyncStatus`. `Retry-After` is not honoured exactly (WorkManager back-off only). Unverified on a device | done | T5 |
| A5 | Triggers: periodic 15 min (unique, KEEP, registered at app start and by `BootReceiver`); `PresenceService` (`CompanionDeviceService`, callbacks for API 31-32 / 33-35 / 36+) enqueues a one-time expedited sync; `DevicePresence.startObserving(context, address)` (presence observation started by the pairing flow after bonding); manual `Scheduler.syncNow` / `Scheduler.uploadNow` (`replace = true` after a network-policy or sink change). Unverified on a device | done | A3 |
| A6 | Car trigger: manifest `CarTrigger` receiver for `ACTION_ACL_CONNECTED`/`DISCONNECTED` (on the implicit-broadcast exception list; exported, since the Bluetooth stack sends from its own uid), filtered to `AppSettings.carDeviceAddress` (case-insensitive); both events → `Scheduler.syncNow` (disconnect: dongle stays awake ~3 min after the trip); 60 s debounce persisted across process death; logic in pure `decideCarTrigger`. Silent until BLUETOOTH_CONNECT is granted. `BondedDevices.list(context)` for the A8 car picker (called by the settings car picker). Unverified on a device | done | A5 |
| A7 | Status screen (`StatusScreen` + `StatusViewModel`, state from `AppGraph.status`/settings flows and the CDM association): paired device (name, address, Forget, pair another), last sync (time, result, pages/lines, stop reason), cursor, pending batches, stored bytes vs cap, dropped lines/bytes, last upload overall and per sink, last error; Sync now / Upload now via `Scheduler` (upload replaces queued work). State-based nav in `MainActivity` (Status → Pairing, Settings; back returns home); the pair wizard starts CDM presence observation after bonding. `SyncStatus` gained `lastSyncPages`, `lastSyncStop`, `lastUploadBySink`. Material 3 with dynamic colour, adaptive launcher icon. Unverified on a device | done | A3, A4 |
| A8 | Settings: sink URL (https only; cleartext off), auth None/Basic/Bearer, secret via Keystore, gzip, cellular toggle, periodic sync, cache cap (10–2000 MB; the JSON index is rewritten per page append), car head unit picker. Known gap: URL and secret are two writes, so an upload starting between them can pair the new URL with the old secret; the replace-enqueue after them bounds the window | done | A7 |
| A9 | Log export: `AppLog` ring buffer (500 entries, 300 chars each, mirrored to `noBackupFilesDir/app.log` and trimmed at 2× so worker entries survive the process); every message redacted (URL user info, Basic/Bearer values, secret query params). `SyncJob`/`UploadJob` log each outcome; pairing logs presence observation. Status screen exports log + store directory listing (sizes and paths, summarised beyond 1000 files) via SAF Save-as or a share intent | done | A7 |
| A10 | Keystore-backed credential storage: `KeystoreSecretStore` (AES-256-GCM key in AndroidKeyStore, ciphertext per secret in `noBackupFilesDir`, undecryptable reads as absent); settings in DataStore Preferences (`AppSettings`: sink URL/auth/user/compress, cellular toggle, cache cap; the device stays in CDM); `SyncStatus` persisted in DataStore. Store: core's `FileStoreDb` in `filesDir/store`, not Room — the index holds page metadata only (~250 B per page part, ~1.3 MB at the 200 MB cap with 40 KB pages, small unless sinks stall for months), pages are separate files, and it is already contract-tested by `DefaultStoreTest`; Room/KSP dropped. Commits are non-cancellable so a worker cancelled mid-commit can't leave the in-memory index behind disk. `FileStoreDb` no longer uses `Files.readString`, which Android lacks | done | A4 |

## 5. First contact with hardware

| id | task | status | deps |
|---|---|---|---|
| H1 | Buy WiCAN Pro; flash `dev-v6`; enable BLE + data logger | blocked (not purchased) | — |
| H2 | Verify `ble_http` on a Pixel 7: CDM pairing scan sees the `WiC_` name (it is in the scan response under legacy advertising; fallback: filter on FFF0 alone); bonded rotating address still connects; `createBond()` shows the passkey dialog (device display-only, default passkey, `pairing_at_boot`); MTU 517 granted; High priority vs device interval; FFF3/FFF4 visible after a fresh post-bond connection; credit pacing and throughput (~60 KB/s expected) | todo | H1, A1 |
| H3 | Record real frame captures into `fakes/` | todo | H2 |
| H4 | Verify sleep behaviour parked 24 h (pin-16 current); note Aux Battery Saver+ wakes | todo | H1 |
| H5 | Verify clock journal on a cold boot without network | todo | H1, W4 |
| H6 | Before first sync, fix the local vehicle profile: `KWH_DISCHARGED` → B53–B56 (upstream has stray B57), `HV_AH_CHARGED` → B40–B43 (upstream has stray B39). Then cross-check one drive against Car Scanner logging via the dongle's ELM327-over-BLE: sign of HV_A/HV_W vs battery power, cumulative kWh/Ah. Report to the homelab ticket; file upstream once confirmed | todo | H1 |

## 6. Release

| id | task | status | deps |
|---|---|---|---|
| R1 | Signed release via GitHub Releases; Obtainium install | todo | A8, S4 |
| R2 | F-Droid metadata + merge request | todo | R1 |

## 7. Additional features

| id | task | status | deps |
|---|---|---|---|
| L1 | OIDC auth: device authorization grant, refresh in Keystore (server is Authelia 4.39) | todo | A10 |
| L2 | `Sftp` transport with Keystore P-256 key, host-key pinning, atomic rename | todo | T4 |
| L3 | `Mqtt` transport (HiveMQ client) | todo | T4 |
| L4 | `HttpPost` transport, `Csv` and `PrometheusText` formats | todo | T4 |
| L5 | `subscribe()` seam for stream/broadcast sources | todo | W2 |
| L6 | Second source: Colmi/jring smart ring | todo | L5 |
| L7 | Upstream reports once confirmed on hardware: EV6 profile byte typos (H6); `/api/logger/export` returning `<file>:0, more:false` when a page ends exactly on a file's last line (worked around in `WicanLoggerSource`); `BLE_HTTP_PROTOCOL.md` §6 head `len` erratum | todo | H2 |

## Homelab side (tracked in the homelab repo, not here)

- Ingest JSONL / JSONL.gz batches next to the existing parser.
- Authelia OIDC client + bearer rule for the upload path (after L1).
