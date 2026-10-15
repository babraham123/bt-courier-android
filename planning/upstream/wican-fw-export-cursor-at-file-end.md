# `/api/logger/export` returns `<file>:0, more:false` when a page ends exactly on a file's last line

**Confirm before filing** (hardware, task H2)
- [ ] Reproduce on a real device with two or more `dl_<epoch>.jsonl` files on the SD card.
- [ ] Record the firmware build/commit tested (branch `dev-v6`).
- [ ] Capture the raw request/response pair (cursor in, `cursor`/`more` out) for the failing page.
- [ ] Confirm it also happens over plain HTTP, not only through the BLE HTTP tunnel.
- [ ] Re-check against current `dev-v6` HEAD (analysis is from `0d92af5`, 2026-10-14); it may already be fixed.
- [ ] Fill in the file/line of the export handler. The public `dev-v6` tree has no logger C source (the endpoint is documented in `components/HTTP_API.md` line 375 only), so cite the doc line unless the source is published.

## Description
When the page budget (`limit`) is exhausted exactly at the last line of a log file that has a newer sibling, the export handler has already advanced to the next file and reset the offset. The response then reports the finished file's epoch at offset 0 with `more:false`.

## Reproduction
1. Have at least two log files `dl_<A>.jsonl` (older) and `dl_<B>.jsonl` (newer).
2. Call `GET /api/logger/export?format=jsonl&stream=params&since=<cursor>&limit=N` with `N` equal to the number of rows remaining in file A.
3. Observe the response: rows of file A, cursor `<A>:0`, `more:false`.
4. Pass `since=<A>:0`: file A is sent again from the start, and the same walk ends the same way.

Evidence: observed 2026-10-13 on `dev-v6`; client-side workaround and analysis in `core/src/main/kotlin/app/btcourier/core/wican/WicanLoggerSource.kt` (`fixFinishedFileCursor`).

## Expected vs actual
| | cursor | more |
|---|---|---|
| Expected | `<B>:0` (next file), or `<A>:<end offset>` | `true` while newer files exist |
| Actual | `<A>:0` | `false` |

A client that follows `more` stops early and one that follows the cursor loops on file A forever.

## Proposed fix
In the export handler, when the budget ends at a file boundary, emit the cursor of the position actually reached (the next file at offset 0, or the previous file at its end offset) and compute `more` from that position. Alternatively, check the budget before stepping to the next file so file and offset stay consistent.

Workaround in use: a page whose cursor has offset 0 and carries rows of that file is rewritten client-side to `<epoch + 1>:0` with `more:true`.

Generated with Claude; verified by hand before filing.
