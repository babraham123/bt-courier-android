# 1. Scheduling rules stay at their seams

Status: accepted

## Context

Several rules decide which work to enqueue, replace or cancel after which event: a settings
change, a sink saved or deleted, a manual sync, a periodic run, a sync that sealed batches, app
start, boot. An architecture review asked whether they belong in one central scheduling module,
`on(event) -> work`, with every caller reporting events to it.

What exists:

- `WorkControl` (`app/work/WorkControl.kt`) is the seam for every scheduling request. It has two
  adapters: `SchedulerWorkControl` (WorkManager, production) and `RecordingWorkControl` (tests).
- Trigger to devices lives in `SyncFanOut` and `syncTargets` (`app/work/SyncTriggers.kt`).
- UI rules live in the view models (`SettingsViewModel`, `SinkEditorViewModel`,
  `StatusViewModel`), behind `WorkControl`.
- Worker rules (periodic fan-out or seal-only, sealed batches enqueue an upload KEEP, retry
  verdict) live in `SyncWork` (`app/work/WorkRules.kt`); `SyncWorker` is a thin adapter.
- What app start and boot re-register lives in `restoreSchedules`, also in `WorkRules.kt`, called
  from both `BtCourierApp` and `BootReceiver`.

## Decision

Keep each scheduling rule at the seam where its inputs live; do not add a central scheduling
module.

A central module would be shallow. The UI conditions ("did the network policy change", "were
the sink's tokens deleted") are known only where that data lives, in the view model. A central
module would need them carried in as event payloads, so the complexity moves into the payloads
instead of concentrating behind a small interface. The trigger to devices mapping already has
its own deep module, `SyncFanOut`. And `WorkControl` is already the seam with two adapters, so a
second seam in front of it adds no leverage.

## Consequences

- Locality: a rule sits next to the data it reads. Changing what a sink save cancels means
  editing `SinkEditorViewModel`; changing what a sync run enqueues means editing `SyncWork`.
- Every rule is tested on the JVM through its module's interface with `RecordingWorkControl`:
  view model tests for UI rules, `SyncTriggersTest` for fan-out, `WorkRulesTest` for worker,
  restore and upload-constraint rules.
- Nothing may call `Scheduler` directly except `SchedulerWorkControl`, so the seam stays the one
  place to observe scheduling in tests.
- Future architecture reviews should not re-suggest a central scheduling module unless a rule
  appears whose inputs span several of these modules.

### Known non-rule

Forgetting a device (`StatusViewModel.forget`) does not cancel its queued sync. This is
harmless: when that sync runs, `SyncJob` no longer finds the device among the paired ones and
ends with `NotPaired` (it only seals).
