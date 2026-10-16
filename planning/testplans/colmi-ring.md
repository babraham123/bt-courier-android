# Hardware test plan: Colmi ring

Settles L6 and L9 in `planning/tasks.md` and the "Unverified on hardware" list in the
`ColmiRingSource` KDoc (cited as *KDoc: <bullet>*). Run in order; each case starts where the
previous one ended. Never commit captures: they hold health data and device addresses.

## Preconditions

- [ ] Colmi R02-family ring (R02..R06), charged to 100 %, worn for 2+ days before case 3.
- [ ] Ideally a second ring on older firmware (case 3 HRV) or of another model (case 1 name, case 3 big data).
- [ ] Vendor app installed but force-stopped, so it does not hold the ring's connection.
- [ ] Android 12+ phone; zone with a non-zero UTC offset (case 5 needs one); automatic time on.
- [ ] Debug APK for cases 1–8, release APK for case 9.
- [ ] An HTTPS PUT sink you control: logs method, path, status, size; objects downloadable.
- [ ] `adb logcat` for lines outside the app log (`PresenceService`, `Worker`).

**Capture** = Status → Save log (diagnostics export: app log + store listing), plus the named
screen text or log line. App log lines look like `<instant> I sync: sync COLMI_RING …`.

## 1. Pair

- [ ] Pair → Smart ring. Note the name in the CDM chooser: `R0x_…` or `COLMI R0x_…`. With a second ring, pair it too and note its form.
- [ ] Both forms appear; a ring whose name matches neither still appears via service `6E40FFF0`.
- [ ] No bond dialog; wizard goes straight to Test.
- Capture: chooser screenshot (crop the address), `pairing: paired; presence observed: true`.
- Settles: L9 names, `6E40FFF0` advertised, OR filter in the CDM chooser, unbonded connect.
- If it fails: `GattProfile.kt` (`NAME_PATTERN`, filter), `CompanionPairing.kt`.

## 2. Test step: battery, firmware, heart-rate setting

- [ ] Explain step: heart-rate checkbox **on** → Test connection.
- [ ] Expect `Battery N%`, `Firmware <x>`, `Hardware <y>`, `Background heart rate: on, every 5 min`.
- [ ] Turn heart rate off in the vendor app, force-stop it, Forget the ring, re-pair with the checkbox **off**.
- [ ] Expect `Background heart rate: off` (the probe only reads when unchecked). `could not be confirmed` = the ring did not answer `0x16`.
- [ ] Re-pair with the checkbox on; open the vendor app, let it sync, force-stop it; Forget and re-pair unchecked: still `on, every 5 min`?
- Capture: Test-step text each round; firmware line verbatim (no serials).
- Settles: L6 write/read-back, setting survives the vendor app, DIS revisions readable unbonded; *KDoc: `0x16` off answer*, *KDoc: Device Information Service*.
- If it fails: `ColmiRingSupport.kt`, `ColmiSettings.kt`, `DeviceInformation.kt`.

## 3. First sync

- [ ] Status → Sync now.
- [ ] Expect `sync COLMI_RING CAUGHT_UP (CAUGHT_UP): 8 pages, …, cursor <today>@<HH:MM>`: day offsets 1..7, then today's slice up to the sync time floored to 5 min.
- [ ] Store listing: one batch per past day, each starting `{"_file":"colmi/<day>"}`.
- [ ] First page has `colmi.battery`, `colmi.hr_logging` (matches case 2), `colmi.device` (matches the Test-step firmware).
- [ ] Rows per day: `colmi.hr` every 5 min while worn, `colmi.steps` per quarter hour, `colmi.hrv` per 30 min (not on the oldest day).
- [ ] Old-firmware ring: no `colmi.hrv` rows, sync still `CAUGHT_UP`, cursor still advances (one ~5 s timeout on the first HRV request). No log line marks HRV unsupported; judge from rows.
- [ ] Big-data present: `colmi.spo2`, `colmi.sleep`, `colmi.temp` rows; plausible values (SpO2 90–100, temp 30–38 °C). Absent (model without it): none, sync still `CAUGHT_UP`.
- [ ] Sync now again within the same 5 minutes: `0 pages`, cursor unchanged.
- [ ] Compare a day's steps total and HR curve with the vendor app.
- Capture: sync line, a day's row counts by param, firmware line beside HRV yes/no, big-data yes/no.
- Settles: *KDoc: historyDays = 8*, *KDoc: steps day offsets*, *KDoc: HRV sub 4*, *KDoc: big-data channel* (UUIDs, models, payloads, write type, temperature scale), *KDoc: write type*, set-time echo (L6).
- If it fails: `ColmiRingSource.kt`, `ColmiPacketCodec.kt`, `ColmiBigData.kt`, `ColmiBigDataGatt.kt`, `ColmiBigDataRequest.kt`.

## 4. Cursor catch-up after disconnect

- [ ] Turn periodic sync off; keep the phone away from the ring for 2 nights; Sync now.
- [ ] Expect `3 pages` (the rest of the last synced day, yesterday, today's slice), cursor = `<today>@<HH:MM>`, no gap or duplicate day in the store listing.
- [ ] Start a sync with 2+ days owed and walk out of range mid-sync.
- [ ] Expect `INTERRUPTED (LINK_LOST)`, then a retry that resumes at the cursor day; that day stored once.
- Capture: both sync lines, cursors before and after.
- Settles: L6 retry-the-day rule, assemblers completing only on whole answers.
- If it fails: `ColmiRingSource.kt`, `ColmiRequest.kt`, `SyncRunner.kt`.

## 5. Day roll-over, sleep across midnight, ring clock

- [ ] Sync at ~23:50 (today's slice), sleep wearing the ring, sync after 00:10: `2 pages`, the rest of the day just ended with its big-data rows, then the new day's first slice.
- [ ] Next morning's sync: `colmi.sleep` row on the wake-up day with `ts` (and `start_ts`) on the previous evening; stages add up to `end_ts − start_ts`; matches the vendor app's night.
- [ ] Clock rule: take the ring off for exactly 13:00–14:00 local, then 2 min of hard exercise at 15:00. Next day: the `colmi.hr` gap must sit at 13:00–14:00 local and the spike at 15:00.
  - Shifted by the UTC offset → the ring stamps real UTC, not wall clock "as if UTC".
  - Correct local but `colmi.steps` shifted a day → the steps offset counts from the ring's today, not the phone's.
- Capture: the hour's `colmi.hr` and `colmi.steps` rows (times only), the `colmi.sleep` row, phone zone offset.
- Settles: *KDoc: ring timestamps as if UTC*, *KDoc: placement from day start*, big-data `daysAgo` and sleep cross-midnight rule.
- If it fails: `ColmiRingSource.kt` (zone, day start), `ColmiBigData.kt` (sleep minutes).

## 6. Intra-day pulls

- [ ] Periodic sync on, ring worn. Mid-morning, Sync now: `1 page`, cursor `<today>@<HH:MM>`, `HH:MM` the sync time floored to 5 min.
- [ ] Store listing: `colmi.hr` rows up to the slot before the cut and none at or after it, i.e. the ring answered today's HR log with the slots so far and zeros after (*KDoc: today's logs*).
- [ ] Leave the phone alone ~75 min: one automatic sync 60–75 min after the cut, the runs before it `NOT_DUE` ("Nothing due until <cut + 1 h>"). Its page adds only slots after the previous cut: no `(ts, param)` already stored, no new `{"_file":…}` marker.
- [ ] A steps quarter in progress at a cut appears once, on the next pull, with its full count (compare with the vendor app).
- [ ] Next morning, once the day has completed and uploaded: no duplicate `(ts, param)` for that day across its objects at the sink; its `colmi.spo2`, `colmi.sleep` and `colmi.temp` rows arrive only after midnight.
- Capture: the sync lines with cursors, the slice's last `colmi.hr` time vs the cut, row count vs distinct `(ts, param)` at the sink.
- Settles: *KDoc: today's logs*, the intra-day rule in the `ColmiRingSource` KDoc.
- If it fails: `ColmiRingSource.kt` (`todayPage`, `SlotWindow`), `SyncJob.kt` (due check).

## 7. Upload and ack

- [ ] Add the HTTPS PUT sink (Jsonl, gzip), on Wi-Fi: Upload now.
- [ ] Expect `upload DELIVERED [<sink id> DELIVERED], 0 batches pending`; one `<sha256>.jsonl.gz` PUT per past day (today's slices go up as they age past the batch age), 2xx.
- [ ] Downloaded object gunzips to JSONL starting `{"_file":"colmi/<day>"}`; every row has `ts_quality":"device"`.
- [ ] Clear app data, re-pair, sync, upload: days already on the sink get the same names; a 409/412 is acked after the HEAD length check.
- Capture: sink access log (method, path, status, size), upload line.
- Settles: end-to-end for the ring source id `colmi:<address>` (L9).
- If it fails: `HttpPutUploadTransport.kt`, `UploadJob.kt`, `Delivery.kt`.

## 8. Triggers and ring battery over a day

- [ ] Presence: phone screen off, leave range 30 min, come back. `adb logcat -s PresenceService`; a sync line follows within minutes. Unbonded ring: does CDM report presence at all?
- [ ] Periodic: on, 24 h. ~96 sync lines expected (Doze stretches it), about one an hour connecting and the rest `NOT_DUE`; out of range = `UNREACHABLE`, never a crash.
- [ ] Car trigger: with a car head unit set, connect it: no ring sync (`syncsOnCarTrigger = false`).
- [ ] Battery: note Test-step `Battery` at 100 % in the morning; next morning's `colmi.battery` row. Repeat one day with periodic off. Difference = cost of hourly syncs. Each connecting sync that stores a slice stores a battery row too, so the day's `colmi.battery` rows also show the drain hour by hour.
- Capture: logcat presence lines, count of sync lines per hour, both battery numbers.
- Settles: L9 presence for an unbonded ring, fan-out by trigger; battery cost (no task row; decides the default period).
- If it fails: `DevicePresence.kt`, `SyncTriggers.kt`, `Scheduler.kt`.

## 9. Release APK on the device (R8)

- [ ] Uninstall debug, install the release APK (signed or unsigned-release), re-pair, sync, upload to the PUT sink.
- [ ] Add an MQTT sink (TLS broker you control) and an SFTP sink (server you control, trust host key on first use, Keystore key): Upload now.
- [ ] Expect `DELIVERED` for each sink; message on the broker, `.jsonl.gz` on the server, no `.part` left.
- [ ] `adb logcat | grep -E 'ClassNotFound|NoSuchMethod|NoSuchAlgorithm|InvalidKey|BC'`: empty. A `BC` provider error = Android's built-in `BC` shadowing sshj's bundled one.
- Capture: upload lines, logcat matches.
- Settles: R1 "shrunk APK's MQTT and SFTP paths", the `BC` provider risk.
- If it fails: `proguard-rules.pro`, `SftpUploadTransport.kt`, `ProviderNeutralEcdsa.kt`, `MqttUploadTransport.kt`.
