# Community Modbus configuration catalog

This is a design reference, not a compatibility promise. It records recurring configuration shapes found in openHAB Community discussions so poller2 can support the useful cases without restoring the legacy Item-specific channel matrix.

## Read-only patterns

| Pattern | Representative configuration/use | Poller2 direction |
|---|---|---|
| Scaled typed register | A Lambda heat-pump configuration reads an `int16` and divides by ten with a JavaScript read transform.[1] | `readTransform` only; no write capability implied. |
| Status plus presentation Item | Thermostat users link typed register values to numbers and sensor status to Contacts.[2] | Keep status/Contact channels read-only. |
| Packed holding-register bit | Users extract a meaningful flag from register bit `X.Y`.[2] | Read-only bit view; preserve raw register context for diagnostics. |
| Counter, energy, measured feedback | Device telemetry may be writable at the protocol level but is not a user command target. | Read-only channel; model a separately documented reset only when needed. |

## Straight write patterns

| Pattern | Representative configuration/use | Poller2 design implication |
|---|---|---|
| Same-address holding-register setpoint | Community examples read and write one setpoint register, sometimes with different read and write types.[1], [2] | Read and write address/codecs must be independently configurable. |
| Write-only command point | Smoke-detector reset and inverter-control examples use a non-polling poller with write-only coil/holding configuration.[3], [4] | `writeOnly` must be valid; it is not a broken read channel. Define health/readback policy explicitly. |
| Force multi-write | Devices may require FC15/FC16 even for one logical coil/register.[1], [4] | Per-write endpoint function-code mode and retry settings are required. |
| Separate read/write coil addresses | A relay configuration reads one address but writes another because of device behavior.[5] | Do not assume a paired point shares one address. |
| Momentary command coil | Roller shutter controls often use separate up/down/stop coils and reset them after a pulse.[7] | Model a command endpoint or pulse policy; do not overload readback position. |

## Complex or advanced patterns

| Pattern | Representative configuration/use | Poller2 boundary |
|---|---|---|
| Multi-register transaction | Float/setpoint examples build an FC16 payload with a custom JSON write transform.[6] | Keep advanced multi-register procedures separate from ordinary scalar Item commands. A future typed action/write-plan should make target address, function, payload, and retry visible. |
| Actual position versus target position | Roller shutter examples read actual position at one address and write target/button commands elsewhere.[7] | Use separate feedback and target/command channels. They are not inverse mappings. |
| Gain/offset plus UoM | Community reports show simple scaling works, while UoM command behavior can require a bidirectional JS profile.[8] | Treat read and write transforms independently; do not assume an inbound display scale is a safe inverse. |

## Poller2 choices derived from the catalog

1. Keep one generic channel model. Do not reintroduce separate semantic channel types for Number, Switch, Contact, Dimmer, or Rollershutter.
1. Use independently configurable read and write endpoint metadata: address, Modbus table, raw codec, transform, retries, and single/multiple function-code mode.
1. Keep `Contact` read-only. A writable coil is a maintained output and can map to a `Switch`; it is not a writable contact.
1. Support one-way read transforms independently of writable capability.
1. Require an explicit write transform or profile for any non-identity/lossy conversion. The poller2 write path strips Item command units and supplies a unitless numeric magnitude to the configured output transform/encoder.
1. Treat write-only endpoints and advanced transactions as explicit supported shapes with their own health, result, and readback policy.
1. Keep physical device feedback authoritative. Do not optimistically replace a polled state with the requested command.

## Open questions to verify in real openHAB

- Exact UI/configuration shape for a managed poller2 write-only endpoint.
- Whether ordinary scalar write configuration needs a readback address/poll or can be intentionally unverified.
- Exact command class reaching a dimensioned poller2 channel before the binding strips units.
- The best durable write-result surface: action/status query versus generic diagnostic state channels.
- Whether an advanced write-plan action should exist in the first writable slice or follow scalar writes.

## Sources

- [1](https://community.openhab.org/t/lambda-heat-pump-in-openhab/156799)
- [2](https://community.openhab.org/t/back-to-openhab-newbie-questions-regarding-modbus-configuration-and-transformation/163950)
- [3](https://community.openhab.org/t/modbus-2-configuration/69584)
- [4](https://community.openhab.org/t/solax-hybrid-inverter-control-through-modbus/157019)
- [5](https://community.openhab.org/t/waveshare-modbus-rtu-relay-16ch/154936)
- [6](https://community.openhab.org/t/solved-problem-writing-floats-to-modbus-with-a-json-writetransform/93433)
- [7](https://community.openhab.org/t/solved-rollershutter-example-and-generalization-js-modbus/75528)
- [8](https://community.openhab.org/t/cannot-figure-out-how-to-sendcommand-to-uom-item-linked-to-a-modbus-thingy-with-a-gainoffset/153773)
