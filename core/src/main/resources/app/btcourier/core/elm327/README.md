# Vehicle profiles

`vehicle_profiles.json` is wican-fw's generated profile set (GPL-3.0, github.com/meatpiHQ/wican-fw,
commit `a2cf687`, 2026-10-13): per car an ELM327 `init` string and the PIDs to poll, each with its
parameters as byte expressions (`B<n>` is the n-th response byte). The ELM327 source polls a
car's profile over the adapter and names each parameter `autopid.<name>`, so rows match what the
WiCAN logger writes for the same car.

Local differences from upstream, pending `planning/upstream/wican-fw-ev6-profile-byte-typos.md`:

| Parameter | Upstream | Here | Profiles |
|---|---|---|---|
| `HV_AH_CHARGED` | `B39<<24` (skips B40) | `B40<<24` | Ioniq5/6 (three variants), Kona, EV6 (two variants) |
| `KWH_DISCHARGED` | `+B57` (skips B56) | `+B56` | same |

Refresh by downloading upstream's `vehicle_profiles.json` and re-applying the table.
