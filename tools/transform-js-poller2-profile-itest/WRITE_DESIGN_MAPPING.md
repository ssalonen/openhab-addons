# Poller2 write design mapping

This mapping turns known configuration/use cases into poller2 design work. It is not an implementation specification yet: entries marked open require a real openHAB check and tests before becoming binding behavior.

Community examples and their source links are collected in [the Community catalog](COMMUNITY_CONFIGURATION_CATALOG.md). Historical write mechanics are read from `origin/modbus-14058`; current legacy behavior is in `ModbusDataThingHandler`.

## Design constraints already chosen

- Keep one generic channel model; do not recreate an Item-specific Number/Switch/Contact/Dimmer/Rollershutter channel matrix.
- Preserve familiar point-owned `readTransform` and `writeTransform` semantics unless compatibility research justifies aliases.
- On writes, strip a quantity unit and pass its numeric magnitude through the configured output transform and raw codec.
- Do not infer a physical device unit from Item metadata, `Number:Power`, a state-description display pattern, or a generated UI ChannelType.
- Polling always publishes device-read data.
- Do not mutate the read cache before a confirmed Modbus write response.
- Do not expose writable Contacts. A maintained output coil may map to a Switch; a Contact is read-only sensor state.

## Use-case mapping

| Use case | Evidence | Poller2 design element | Required behavior | Still open |
|---|---|---|---|---|
| Same-address scalar setpoint | Community devices use one register for feedback and command; some use different raw read/write types. | Optional per-channel `write` endpoint. | Read and write address/codecs are independently configurable. Command dispatch is by Channel UID. | Non-integral/overflow behavior: preserve existing codec semantics initially; do not impose a new reject/clamp/round policy silently. |
| Read-only scaled engineering value | Typed register plus `readTransform`, such as divide-by-ten. | Point-owned `readTransform`. | Read conversion works without write support. | Exact YAML transform-list syntax and error messages. |
| Scaled or UoM setpoint | Community reports show that a display/UoM profile is not always a safe reverse conversion. | `writeTransform` or bidirectional link profile. | Units are stripped before point-owned output transform; transform result must be consumable by the selected raw codec. | Whether transforms see a plain number only or support an explicit typed output grammar. |
| Maintained writable coil | Relay/inverter control examples. | Coil write endpoint. | FC05 or FC15, optional inversion/output transform, write retries. | Which non-ON/OFF commands, if any, are admitted without an explicit transform. |
| Separate feedback and command coils | Community relay examples use different read/write addresses. | Independent read and write declarations. | Never infer write address from read address. | One logical channel with separate endpoint config versus separate feedback/command channels in Main UI. |
| Momentary coil/pulse | Roller shutter and reset examples. | Explicit command endpoint or future action. | Do not treat a pulse as the inverse of a feedback state. | Whether pulse/reset is first-slice configuration, a typed action, or a rule/profile concern. |
| Write-only point | Reset/control blocks with polling disabled. | Explicit write-only endpoint shape. | Valid configuration; no false read failure just because it does not poll. | Health, readback, and Main UI configuration policy. |
| Partial holding-register bit | Historical and legacy code construct a full register from cache and one bit change. | Dedicated bit-overlay writer. | Require compatible successful read cache; serialize read-modify-write with poll application and all same-poller writes. | Cache freshness, forced read versus reject, pending overlay lifetime, and external-writer warning. |
| Forced multiple write | Devices require FC15/FC16 even for a scalar. | Per-write function-code mode and write retry config. | Do not infer single/multiple only from payload length. | Exact config names/defaults. |
| Advanced multi-register transaction | JSON write transforms build FC16 payloads and override normal write address. | Future typed write-plan/action. | Keep ordinary Item commands scalar and predictable. Advanced plans explicitly own function, target address, values, retries, and result. | Include in first write slice or defer. |
| Actual feedback versus target | Roller shutter examples use separate position feedback and target/button writes. | Separate feedback and target/command channels. | Device feedback remains authoritative; no optimistic replacement of read state. | Optional target/acknowledged state model. |
| Counter, energy, alarm, enum/status | Telemetry/many-to-one states are not natural commands. | Read-only default. | A reversible generic command needs a documented one-to-one mapping. | Explicit enum table syntax and protected reset/ack behavior. |

## Write lifecycle

```text
Item command
→ reject REFRESH as a write
→ strip QuantityType unit to numeric magnitude
→ writeTransform (optional)
→ existing raw codec conversion
→ poller-scoped serialized write submission
→ write response
→ confirmed pending overlay, if needed for a later same-poller bit write
→ non-cached reconciliation poll
→ publish actual device data
```

The read cache contains only successful poll data. Confirmed write overlays are distinct from the read cache and exist only to safely construct later serialized partial-register writes before readback arrives.

## Outcome mapping

| Situation | State Channel | Poller Thing status | Outcome record |
|---|---|---|---|
| Transform/config/unsupported-codec rejection | unchanged | configuration errors only when configuration itself is invalid; otherwise remains online | rejected; no request submitted |
| Modbus transport failure | last/next device read remains authoritative | offline / communication error | transport failure with request identity and message |
| Modbus write response succeeds | do not optimistically replace feedback state | online or restored online | write confirmed; reconciliation pending |
| Reconciliation read matches | publish polled device state | online | readback confirmed |
| Reconciliation read differs | publish actual device state | normally online | reconciliation mismatch with expected/observed raw data |

A channel trigger is not the primary write-result surface. It cannot carry durable correlated outcome data. Preferred surfaces are Thing status for connectivity, structured action/query outcomes, and logs; diagnostic state channels remain a later choice.

## Minimum first writable slice

1. One holding-register scalar command channel with an independent optional write endpoint.
1. One maintained coil command channel.
1. Point-owned `writeTransform`, using established syntax.
1. Existing raw codec behavior for supported types; no new range/rounding policy.
1. Per-write retries and forced single/multiple mode.
1. No optimistic state/cache replacement; reconciliation poll after confirmed write.
1. End-to-end responder tests that record function code, address, payload, failure, and later readback.

Defer pulse policy, advanced multi-write plans, finite enums, percentage/position mappings, write-only UI workflow, and partial-register-bit writes until their explicit tests and policies are agreed.

## Verification work

The full live-runtime matrix is maintained in [PENDING.md](PENDING.md), especially `OH-CMD-*`, `OH-ACTION-*`, and `OH-UI-03`.
