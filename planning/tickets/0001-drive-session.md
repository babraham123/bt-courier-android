# Drive session: keep pulling from the WiCAN while the car is in use

Status: implemented (unverified on hardware), with the amendments below (task L10). File as a GitHub issue with `gh issue create --title "..." -F planning/tickets/0001-drive-session.md`.

## Problem

While driving, the dongle is in range the whole time but the app syncs it at most every 15 minutes:
CompanionDeviceManager presence fires once when the dongle appears, the car trigger fires on head-unit
connect and disconnect, and the periodic work runs on WorkManager's 15-minute floor. Rows logged during
a drive therefore reach the sinks in 15-minute chunks at best, plus the seal and upload tail. Exact
alarms are not an option: `USE_EXACT_ALARM` is reserved for alarm and calendar apps under Play policy,
and `setExactAndAllowWhileIdle` is throttled to about once per 9 minutes in Doze.

## Proposal

A drive session: a `connectedDevice` foreground service that holds the BLE link to the dongle and pulls
on a short interval until the drive ends. Android allows a long-running foreground service of this type
while the companion device is connected; the manifest already declares `connectedDevice|dataSync` and
`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`.

- **Start**: car head-unit connect (`CarTrigger`) or WiCAN presence (`PresenceService`), when the
  setting is on. Starting from a presence callback is allowed for a CDM companion.
- **Loop**: open the link once, then every `pullInterval` (setting, default 2 min; 1–10) run the
  existing `SyncRunner` from the stored cursor on the open link, seal with the configured policy, and
  enqueue an upload when anything sealed (the same rule as `SyncWork`). Cellular use follows the
  existing upload setting.
- **Stop**: car head-unit disconnect (after the existing 60 s debounce, and one last pull because
  the dongle stays awake about 3 minutes), link lost and not reopened within 2 attempts, `maxIdlePulls`
  (default 5) consecutive pulls that stored nothing, or `maxSessionMinutes` (default 180) as a hard cap.
  The periodic and presence syncs keep running as a fallback and are no-ops while the session holds
  the device lock (`DeviceLocks`, `SyncOutcome.AlreadyRunning`).
- **Status**: the status screen shows "Drive session: N pulls, M lines, since HH:MM"; the foreground
  notification carries the same line and a Stop action.
- **Seam**: session rules (start, pull, stop) live in a JVM-testable `DriveSession` class in
  `app/work/` next to `SyncJob`, behind `WorkControl` per ADR 0001; the service is a thin adapter.
- **Settings**: "Keep syncing while driving" toggle (default off until verified on hardware),
  interval, and the idle cap.

## Amendments (as implemented)

- **Live modes.** `DeviceSupport.liveMode: LiveMode {NONE, PULL, STREAM}`. WiCAN is `PULL` (the loop
  above), the Colmi ring `NONE` (never in a session), and a streaming kind such as the ELM327 of
  ticket 0002 is `STREAM`: the session collects `Source.subscribe()` and stores each emission
  through core `StreamRecorder` (one page per emission with rows, cursor
  `session:<start ms>:<n>`, the first under `{"_file":"<kind>/<start ms>"}`, normalised with an
  empty journal and no anchor). A streaming kind sets its own polling pace and is never synced by
  a trigger, since it keeps nothing to pull.
- **Idle rule as a duration.** `maxIdlePulls` became `driveIdleMinutes` (default 10, 2–60): the
  session ends once no row has been stored for that long, which works for both modes (with the
  defaults it is the same five idle pulls). Settings: `driveSession` (default off),
  `drivePullIntervalMinutes` (default 2, 1–10, `PULL` only), `driveIdleMinutes`,
  `driveMaxSessionMinutes` (default 180, 30–480).
- **Rounds, not pulls.** The status line counts rounds (pull rounds or stream emissions) and rows:
  "Drive session: N rounds, M rows, since HH:MM", or the last session's end time and reason.
- **Manual sync.** A manual sync that finds the session's lock asks the session for a round now
  (`SyncOutcome.HandedToSession`); automatic syncs stay no-ops (`AlreadyRunning`), and presence
  or the car connecting enqueue nothing for a device already in a session.
- **Link loss.** A pull round whose response was cut off counts as a lost link too; each loss gets
  up to 2 reopen attempts 10 s apart, and a link that comes back is pulled at once.
- **Refused start.** If the system refuses the foreground service start, the trigger enqueues
  the sync it would have enqueued with the setting off.

## Prerequisite

`planning/testplans/wican-pro.md` case 3 must show whether `/api/logger/export` serves rows from the
file the logger is still writing. If it only serves closed files, a session gets nothing new until the
dongle rotates the file, and the lever is the dongle's rotation setting (or a firmware change filed
under `planning/upstream/`), not this feature.

## Acceptance

- With the toggle on and a 2-minute interval, rows logged during a 30-minute drive appear at a sink
  within interval + seal age + upload time of being logged, in at least 10 batches.
- With the toggle off, behaviour is unchanged (15-minute cadence).
- A session ends within one interval of head-unit disconnect and within `maxIdlePulls` intervals of
  the dongle going quiet; the notification disappears with it.
- No second BLE link is opened to the dongle by a periodic, presence or manual sync during a session;
  a manual sync during a session triggers an immediate pull instead.
- Battery: a 1-hour session costs the phone no more than a 1-hour A2DP connection order of magnitude
  (measure with Battery Historian on the test phone).
- Unit tests cover start, stop and the idle cap through `DriveSession` with `RecordingWorkControl`
  and the fake link; the hardware test plan gets a case.

## Out of scope

Pull cadence below 1 minute, the Colmi ring (its data is per day), and the WiCAN's own Wi-Fi/MQTT
push over a phone hotspot, which sidesteps BLE entirely and belongs in the dongle's configuration.
