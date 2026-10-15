# Kia EV6 / Ioniq 5-6 profile: stray byte indexes in `KWH_DISCHARGED` and `HV_AH_CHARGED`

**Confirm before filing** (hardware, task H6)
- [ ] Log a drive with the corrected profile and confirm `KWH_DISCHARGED` is a sane monotonic cumulative value (B53-B56).
- [ ] Confirm `HV_AH_CHARGED` is sane and monotonic (B40-B43).
- [ ] Capture the raw PID response bytes showing the byte positions.
- [ ] Dual-log one drive with Car Scanner (ELM327 over BLE) and compare cumulative kWh/Ah.
- [ ] Determine the sign convention of `HV_A` / `HV_W` against battery power (discharge vs charge); mention only if it differs from the profile's intent.
- [ ] Confirm the vehicle variant/model year tested (no VIN).
- [ ] Re-check current `main` of `vehicle_profiles/hyundai/ioniq5-6.json` (line numbers below are from commit `a2cf687`, 2026-10-13).
- [ ] `CAPACITOR` on line 61 is `((B63<<8)+B65)`, skipping B64; check whether it is a third typo (B63-B64) or intentional before adding it to the report.

## Description
Two parameters in `vehicle_profiles/hyundai/ioniq5-6.json` (wican-fw `main`, `car_model` "Hyundai: Ioniq5/Ioniq6 (2021-2024)", also used for the Kia EV6) have one stray byte index each in their 4-byte cumulative counters, producing wrong values.

## Evidence
| Line | Parameter | Upstream expression | Should be |
|---|---|---|---|
| 55 | `HV_AH_CHARGED` | `((B39<<24)+(B41<<16)+(B42<<8)+B43)/10` | `((B40<<24)+(B41<<16)+(B42<<8)+B43)/10` |
| 58 | `KWH_DISCHARGED` | `((B53<<24)+(B54<<16)+(B55<<8)+B57)/10` | `((B53<<24)+(B54<<16)+(B55<<8)+B56)/10` |

The neighbouring counters are contiguous 4-byte fields (`HV_AH_DISCHARGED` B44-B47, `KWH_CHARGED` B49-B52), which makes the gaps at B40 and B56 the odd ones out.

## Expected vs actual
- Expected: each cumulative counter is a 4-byte field decoded from contiguous bytes.
- Actual: `HV_AH_CHARGED` takes its high byte from B39 (skipping B40) and `KWH_DISCHARGED` its low byte from B57 (skipping B56), so both values are off. <Fill in observed values from hardware.>

## Proposed fix
- `KWH_DISCHARGED`: use B53-B56.
- `HV_AH_CHARGED`: use B40-B43.
- If the dual-log shows HV_A/HV_W sign is inverted relative to the documented convention, note it or flip it in the same PR.

Generated with Claude; verified by hand before filing.
