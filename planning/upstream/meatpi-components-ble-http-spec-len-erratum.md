# `ble_http/BLE_HTTP_PROTOCOL.md` §6: head frame `len` values do not match the printed JSON

**Confirm before filing**
- [ ] Re-read the current §6 and confirm the header bytes are still `1C 00` and `2E 00` (`components/ble_http/BLE_HTTP_PROTOCOL.md` lines 177-178 at `d8cd667`, 2026-10-14).
- [ ] Count the JSON payload bytes again (no trailing newline, exact spacing).
- [ ] Confirm on hardware (H2) that the device sends `len` = payload size (captured RSP head frame).
- [ ] Check the body-frame example (`45 04` = 1093) is consistent and needs no change.

## Description
§2 defines the 8-byte header as magic, version, type, flags, seq, `len` (u16 little-endian, payload length). In the §6 worked example `GET /api/status`, the head frames print `len` values that do not equal the length of the JSON shown next to them.

## Evidence
| Frame | Header bytes in §6 | `len` printed | JSON shown | Actual JSON size |
|---|---|---|---|---|
| REQ | `57 02 01 00 01 00 1C 00` | 28 | `{"m":"GET","p":"/api/status"}` | 29 |
| RSP | `57 02 03 00 01 00 2E 00` | 46 | `{"s":200,"ct":"application/json","len":1093}` | 44 |

The firmware builds `len` from the payload it sends (`bleh_hdr_build` with the `snprintf` length of `bleh_rsp_head`), so the implementation agrees with §2 and the examples are wrong. A client test suite that copies the §6 bytes verbatim fails unless it recomputes `len` (see `core/src/test/kotlin/app/btcourier/core/transport/blehttp/WireVectorsTest.kt` in the reporter's repo).

## Expected vs actual
- Expected: the printed `len` equals the payload length: `1D 00` (29) for REQ, `2C 00` (44) for RSP.
- Actual: `1C 00` and `2E 00`.

## Proposed fix
Change the §6 header bytes to `57 02 01 00 01 00 1D 00` and `57 02 03 00 01 00 2C 00`, or adjust the JSON/spacing so it matches the printed lengths.

Generated with Claude; verified by hand before filing.
