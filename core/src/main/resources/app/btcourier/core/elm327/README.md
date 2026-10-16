# Vehicle profiles

`vehicle_profiles.json` is wican-fw's generated profile set (GPL-3.0, github.com/meatpiHQ/wican-fw,
commit `a2cf687`, 2026-10-13): per car an ELM327 `init` string and the PIDs to poll, each with its
parameters as byte expressions (`B<n>` is the n-th response byte). The ELM327 source polls a
car's profile over the adapter and names each parameter `autopid.<name>`, so rows match what the
WiCAN logger writes for the same car.

The file is upstream's, unchanged. Byte indexes count the PCI byte of every CAN frame (see
`Elm327Response`), so in a multi-frame answer B0, B8, B16, ... are PCIs, and a multi-byte field
that crosses a frame skips one: the Ioniq5/6, Kona and EV6 counters `HV_AH_CHARGED`
(`B39, B41-B43`) and `KWH_DISCHARGED` (`B53-B55, B57`) and `CAPACITOR` (`B63, B65`) are contiguous
in the UDS payload, not typos.

Refresh by downloading upstream's `vehicle_profiles.json`.
