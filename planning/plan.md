# Plan: BT Courier

BT Courier is an Android app that pulls logged data from BLE devices (first: MeatPi WiCAN Pro OBD
dongle), caches it locally, and uploads it to self-hosted backends. Nothing in
the core is specific to cars.

## Goals

- Catch up after a disconnect or a drive without the phone: the device keeps
  history, the app pulls from a cursor until `more:false`.
- Connect automatically when the device is in range; no foreground service
  while idle.
- Upload in batches over Wi-Fi (cellular optional), with a bounded local cache.
- Repair timestamps logged before the device's clock was valid.
- Transports, sources, sinks and formats are plugins.

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Hardware | WiCAN Pro (ESP32-S3, wican-fw `dev-v6`) | Original WiCAN's BLE is ELM327-only with no storage. Pro serves the whole `/api/*` over BLE (`ble_http`) and logs to SD |
| Device protocol | HTTP API over BLE, not ELM327 | Dongle polls and logs by itself; phone syncs for seconds |
| Stack | Kotlin, Jetpack Compose, pure-Kotlin `core/` module (KMP-ready) | Background auto-connect needs `CompanionDeviceManager`, bonded MITM link and WorkManager; RN/Flutter BLE libs don't expose these |
| Platform | Android only; min API 31, target current | iOS deferred |
| Distribution | GitHub Releases + Obtainium, then F-Droid; reproducible builds from day one | No Play Store hoops; F-Droid re-signs otherwise |
| License | GPL-3.0 | wican-fw is GPLv3; porting its clock-repair semantics is then unambiguous |
| Uploads | Batches, not streams | Data arrives per sync; batches are resumable, idempotent and suit WorkManager |
| Compression | gzip at batch seal, not per page | Pages are ≤64 KB; sealed batch is also the upload unit |
| Deps | FOSS only, no Play Services | F-Droid |

## Architecture

```
Transport ──▶ Source ──▶ Normalizer ──▶ Store ──▶ Sink = Transport × Format
BLE / Wi-Fi   WiCAN      clock repair   SQLite    HttpPut/HttpPost/Sftp/Mqtt/LocalFile
                                                  × Jsonl/Csv/PrometheusText/Raw
```

| Layer | Interface | First implementation |
|---|---|---|
| Transport (device) | `request(method, path, body?) → Response` | `BleHttp` (spec: `meatpi-components/ble_http/BLE_HTTP_PROTOCOL.md`; device→app on FFF3 notify, app→device writes on FFF4; 8-byte frame header, types REQ/REQ_BODY/RSP/RSP_BODY/ABORT/CREDIT; 4-bit body counter; CREDIT every 4 KB, 16 KB window; 30 s without credit → device sends early LAST, seen as `Truncated`), `WifiHttp` (same API over LAN/VPN) |
| Source | `pull(cursor: String) → Page(records, nextCursor, more)`, `clockJournal()` | `WicanLogger`: `/api/logger/export?format=jsonl&stream=params&since=&limit=`, journal from `/api/fs/download?path=/sd/logs/clock.jsonl`. Cursor is opaque to core; WiCAN's is `<file epoch>:<byte offset>` |
| Normalizer | `fix(page, journal, anchor, openFile) → lines to store + rows + lastFile` (markers kept in place; `core/lines/StoredLine` is the one definition of marker vs row) | Per-`_file` re-basing by journal `delta_ms` (semantics of wican-fw `tools/log_clock.py`), plus a per-sync anchor (phone time vs device `/api/status` time). `ts_quality ∈ {journal, anchor, unknown}` |
| Store | append page, persist cursor atomically, seal batches, per-sink ack, byte cap | core `DefaultStore` on an in-memory snapshot behind one `SnapshotPersistence` seam (`FilePersistence`: index.json + one file per page, temp-then-rename; `MemoryPersistence` for tests; Room dropped, see tasks A10). Pages keyed by `_cursor` (idempotency). Seal on `_file` boundary, 1 MB, or the upload-delay setting (0 by default, so every seal closes what is open; a non-zero delay gets its own scheduled upload). Cap 200 MB, oldest-first eviction with a dropped counter |
| Sink | `upload(batch) → ack`; `Delivery` owns per-sink retry state (streak, `Retry-After`) across rounds and, through `RetryStateStore` (the app's in the status DataStore), across process restarts; transports only classify | Transports `HttpPut`, `HttpPost`, `Sftp` (sshj, pinned host key, Keystore P-256 key), `Mqtt` (HiveMQ, QoS 1), `LocalFolder` (SAF); formats `Jsonl`, `Csv`, `PrometheusText`; any transport × any format, several sinks at once, each with its own ack id |
| Auth | `None \| Basic \| Bearer \| Oidc` per sink; `SinkCredentials` owns every per-sink credential (names, SFTP key alias, token rotation, SAF grant release) | OIDC via device authorization grant (RFC 8628), tokens and client secret in the Keystore-backed secret store, refreshed in the background; MQTT uses Basic as its username/password; SFTP authenticates with the Keystore key |
| Scheduler | triggers → sync; constraints → upload | CDM device presence (that device), periodic 15 min (every device), manual, car ACL connect/disconnect (WiCAN devices only); `SyncJob` returns one `SyncOutcome` with the status record and the retry verdict (`SyncRetryPolicy`: 429 retried 1/2/4 s on the link, interrupted runs re-run up to 5 times); one sync per device with a per-device lock shared with the pair wizard's probe; an automatic sync skips connecting before its kind's `nextDueAtMs` from the stored cursor (the ring: an hour after its cursor's cut; WiCAN: always due), a manual one always connects; upload `UNMETERED` by default with cellular toggle |
| UI | pair wizard (device kind first), status per device, settings with a sink list and a generic sink editor, log export | Compose; per-device-kind behaviour (profile, filter, source, probe, labels) behind `app/device/DeviceSupport`; per-transport config, fields, validation and build behind `app/data/sinkkind/SinkTransportKind` |

### Sync loop

1. Connect (bonded, encrypted, MITM).
2. `GET /api/logger` for clock state.
3. Loop `export?since=<cursor>&limit=N`; persist page + cursor in one transaction; stop at `more:false`.
4. Disconnect. (Implemented order: `/api/rtc` anchor and journal are fetched
   BEFORE the loop because the store holds normalised lines and the device trims
   old journal events; rows pulled before the clock event that would re-base
   them keep `anchor` quality. Device time is on `GET /api/rtc`, not `/api/status`.)
5. On link loss: resume from the persisted cursor; duplicate pages dedupe on `_cursor`. 429: back off and retry.

### Guarantees to backends

- Batches are disjoint: pages are keyed by cursor and deduped before sealing,
  so a row appears in exactly one batch. A re-uploaded batch is byte-identical
  (stable sha256), so receivers can dedupe by content hash.
- Each batch starts with the device's `{"_file":...}` marker line and is
  sealed at file boundaries; a logical session may still span batches.
- `Jsonl` rows carry the re-based `ts` (JSON null when unknown), `ts_quality`
  (`journal` | `device` | `anchor` | `unknown`), and `ts_raw`. Param
  names are the device's own; renaming and session/trip derivation (e.g. gap
  > 10 min) belong to the receiver.
- Objects are named with the content extension (`.jsonl.gz`); nothing relies
  on `Content-Encoding`.

### Source classes (future)

| Class | Cursor | Examples |
|---|---|---|
| Pull with history | opaque | WiCAN logger; Colmi/jring smart rings (per-day); Aranet4 |
| Stream while connected | none | HR straps, ELM327 live |
| Broadcast | none | Ruuvi, TPMS, Victron |

Class 1 is built for the WiCAN logger and the Colmi ring (`DeviceKind` picks the GATT profile, source and store source id `wican:`/`colmi:<address>`); `subscribe()` exists on `Source` but nothing collects it yet.

### Car trigger

`BluetoothDevice.ACTION_ACL_CONNECTED/DISCONNECTED`, filtered to the car head
unit's address, are exempt from implicit-broadcast limits and reach a manifest
receiver. Connect = in the car, dongle awake: enqueue a sync. Disconnect = trip
ended, dongle stays awake ~3 min: sync now so the trip lands before it sleeps.
CDM presence on the dongle itself remains the primary trigger.

## Non-goals (v1)

iOS, Play Store, live streaming, broadcast sources, daily cellular cap.

## Constraints

- No PII in this repo: no VINs, no account names, no hostnames, no sample trips.
- Untrusted firmware clones are read-only; run any Python from them with `-I`.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
- Server-side ingest changes live in the homelab repo, not here.

## Status

Core and app are implemented and unit-tested (2026-10-15; see `tasks.md`), including the section 7 features (OIDC, SFTP, MQTT, HTTP POST, CSV/Prometheus, SAF folder, smart ring) and the section 8 deepenings (one module each for delivery retries, stored lines, device kinds, sink credentials, transport kinds, sync outcome, store persistence).
All protocol facts come from docs and code read 2026-10-14 (firmware commits
2026-10-13/14). Nothing verified on a device; first-contact tasks are in
`tasks.md` section 5. Known upstream sleep bug: wican-fw issue #718. Upstream
findings to report after confirmation are in task L7.
