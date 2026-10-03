# Poller2 pending design and real openHAB verification ledger

This file separates established runtime evidence from proposals. Do not promote a proposal to binding behavior until the corresponding real openHAB check and automated test are complete.

## Established in the live disposable runtime

| Topic | Result | Evidence |
|---|---|---|
| YAML dynamic channel | `itemType: Number` plus `itemDimension: Power` produced a compatible `Number:Power` channel. | Live YAML model and poller2 state updates. |
| Main UI compatibility | A generated runtime ChannelType made the YAML channel visible and linkable without changing its YAML declaration. | Authenticated Main UI Channels and Add Link flow. |
| Direct no-profile link | Raw `DecimalType(42)` became `42 W` for a `Number:Power` Item with unit `W`, and `42 MW` for one with unit `MW`. | Main UI Item states. |
| Display conversion | An Item storing `42 MW` with pattern `%.3f kW` displayed `42000.000 kW`. | Main UI channel-linked Item row. |
| Script profile state path | Explicit JavaScript result `Number(input) / 10 + ' W'` produced `4.2 W`. | Live responder, Core profile route, Item state. |
| Available profile choices | Main UI offered Default, Follow, Gain-Offset Correction, Hysteresis, Offset, Range, ECMAScript, Rule DSL, Timestamp on Change, and Timestamp on Update. | Main UI Add Link form. |
| Current poller2 commands | A `1 MW` Item command returned HTTP 200 but caused no Modbus write and the next poll restored `42 MW`. | Live runtime and current inherited no-op `handleCommand()`. |

## Write design questions

### W1: Bare numeric commands and units

Decision: poller2's write path strips any command unit and sends the numeric magnitude through the configured transformation and raw-value encoder. The Item's unit, the Channel's accepted Item type, and state-description display metadata are not device/protocol-unit declarations.

Consequences:

- bare `42`, `42 W`, and `42 MW` all enter the raw write pipeline as numeric `42` unless a configured output transformation deliberately distinguishes or converts them;
- a user who needs `kW → W`, scale/offset, enum, or Boolean conversion configures an explicit output transformation or bidirectional profile;
- poller2 does not add a channel-level physical-device-unit field merely to make writes work.

Core accepts a bare numeric command for a dimensioned Number Item, so this is a poller2 policy choice rather than a Core limitation. The real-runtime check remains: record the exact command object before poller2 strips it, then prove the configured transform/encoder receives the expected numeric input.

### W2: Transformations around the raw encoder

Decision: add explicit input and output transformation support to poller2 before writable channel support. Reuse familiar openHAB transformation syntax and service semantics rather than inventing a binding-specific expression language.

Pending naming/API choice:

- preserve established Modbus terms `readTransform` and `writeTransform`; or
- add clearer aliases such as `inputTransform` and `outputTransform` while retaining the established terms for compatibility.

Required pipeline:

```text
poll response → raw typed decode → input/read transform → updateState
Item command → strip unit to numeric magnitude → output/write transform → raw codec using established conversion behavior → Modbus write
```

A profile remains the alternative when the mapping belongs to one Item-channel relationship rather than the device point itself. A binding transform is point-owned and may be one-way; a profile used for writable data must supply a tested reverse mapping.

Do not add a new poller2 range/rounding rejection layer by default. The established Core `ModbusBitUtilities.commandToRegisters` encoder converts numeric values through Java numeric conversions for supported 16/32/64-bit types; it does not currently impose representable-range rejection or configurable rounding. Poller2 should preserve that behavior initially, reject only unsupported command/codec/transform results, and add warnings or stricter policies only after a compatibility decision and tests.

### W3: Same-poller write semantics

Questions:

- Which operations are exposed as ordinary Item commands versus typed `ThingActions`?
- How are full-register, coil, and partial-register-bit writes represented?
- What is the pending-cache lifecycle after an accepted write?
- What happens if the next poll disagrees with the optimistic value?
- Are writes rejected without the raw cache needed for a read-modify-write overlay?

Provisional behavior to evaluate:

1. Serialize all writes through the existing poller/endpoint queue.
1. Reject a partial register-bit write without a successful current cache entry.
1. Do not update the read cache before confirmed transport success.
1. Polling always publishes received device data, including data that disagrees with a prior write.
1. After a confirmed write, retain only a separate pending-write record until the next poll reconciles it; never replace device-read cache with an unconfirmed value.
1. Never claim atomic coordination across different pollers or external Modbus clients.

### W4: Write outcome and error reporting

A write outcome is separate from the device state. For example, a user commands `50` and the Modbus write times out:

```text
power state channel:       continues to show last/next device poll value
poller Thing status:       OFFLINE / COMMUNICATION_ERROR: last write to power timed out
write outcome query/action: channel=power, phase=transportFailure, requestId=..., message=...
next successful poll:      publishes device state and restores ONLINE
```

This prevents a failed command from replacing a meaningful measured state with `UNDEF`. Conversely, a local command/transform/encoder problem sends no request:

```text
power state channel:       unchanged
poller Thing status:       remains ONLINE
write outcome query/action: channel=power, phase=rejected, reason=...
```

Provisional reporting surfaces:

- Thing status only for transport/connectivity health, with its latest operational description;
- structured outcome result for actions and a queryable latest outcome per channel;
- structured logs for rejected, failed, and reconciliation-mismatch writes;
- no channel trigger as the primary write-error contract. A trigger can notify a rule but cannot preserve request correlation, durable status, or a caller-visible result.

Coil clarification: a coil is a Modbus writable output bit and can legitimately map to a maintained `Switch` command. A `Contact` represents read-only open/closed sensor state and is not a command contract. Do not expose a Contact write channel.

### W5: Conversion ownership

Question: Which mappings should be binding-owned, profile-owned, or left to generic scripting?

Provisional split:

| Mapping | Read-only channel mapping | Reversible channel/profile mapping | Notes |
|---|---|---|---|
| Raw numeric scale/offset | yes | only with explicit inverse, unit, rounding, and bounds | Common register engineering-unit representation. |
| Fixed unit attachment | yes | only if commands convert compatible `QuantityType`s to the declared physical unit | Item metadata alone is insufficient. |
| Threshold to Switch | yes | yes only with explicit `onValue` and `offValue` | Hysteresis is read-side stateful and not generally invertible. |
| Enum/status text | yes | no by default | Reverse mapping requires an explicit one-to-one command table. |
| Bit field/flags | yes | partial write only through cache-backed overlays | One flag command must preserve unrelated bits. |
| Counter/energy total | yes | no | Device-owned cumulative values should not generally be written. |
| Percent/position | yes | yes only with explicit raw endpoints and rounding | Must distinguish position, dimmer, and device-specific modes. |
| Calendar/time/BCD | yes | only with explicit timezone/epoch/format policy | Locale/timezone ambiguity makes generic transforms unsafe. |
| Arbitrary JS | yes | allowed only as user-owned script with tested quantity parsing | Do not add a binding shorthand that silently strips units. |

Research must turn this table into a bounded supported catalog or explicitly leave cases as raw data plus user scripting.

## Research findings awaiting implementation decisions

### Core bare-number command routing

Source inspection of the current Core checkout establishes that `Number:<Dimension>` Items accept both `DecimalType` and `QuantityType` commands. A bare command such as `42` is accepted as `DecimalType`; it is not rejected merely because the Item is dimensioned.

For a direct link, Core's UoM adaptation depends on the Channel contract:

| Channel accepted type | Item command | Handler-facing form |
|---|---|---|
| `Number` | bare `5` or `5 kW` | `DecimalType(5)`; quantity input is stripped to its numeric value |
| `Number:Power` | bare `5` | `QuantityType(5, Item unit)` |
| `Number:Power` | explicit quantity | quantity command, subject to normal compatible-unit routing |

Design consequence: a writable poller2 Channel that exposes `Number:Power` can receive a quantity. A raw/unitless `Number` channel instead receives a decimal and must not be presented as a physical-unit contract. The outstanding real-runtime test is to record the exact command object at a writable poller2 handler for each row of this table.

### Historical OH4 refactor (`origin/modbus-14058`)

The historical branch already used per-channel write declarations and a poller dispatcher that routed non-`REFRESH` commands to channel-specific write handlers. It supported typed coil/full-register writes, explicit write-multiple/retry controls, and cache-backed partial register-bit writes.

Reuse as design input:

- per-channel write direction and raw encoding declarations;
- command-to-raw conversion separate from transport submission;
- strict validation for coil, full-register, and `X.Y` bit-overlay addresses;
- FC05/FC06 versus FC15/FC16 selection and retry controls.

Do not carry forward:

- duplicated Item-specific channel-type matrices created only for old Main UI compatibility;
- optimistic cache mutation before a write response succeeds;
- unit handling that accepts a `QuantityType` but ignores its unit;
- any XML-declared write channel without an end-to-end dispatcher test.

The historical implementation did not answer poller2's quantity conversion question. Its register writes accepted `DecimalType`, while a coil path accepted quantity values without normalizing units. That is not a safe contract for the new model.

### Candidate bounded conversion catalog

Start with direct typed raw values plus these intentionally small mappings:

| Category | Read-only mapping | Reversible mapping allowed? | Required guardrail |
|---|---|---|---|
| Identity scalar | typed register to Number | yes | exact raw width, signedness, word order, range |
| Affine engineering value | raw scale/offset to quantity | yes | declared physical unit, inverse scale, rounding, min/max, representable step |
| Boolean coil | bit to Switch | yes | explicit normal/inverted polarity |
| Register bit | `X.Y` to Switch/Contact | only cache-backed overlay | current cache, serialized read-modify-write, preserve adjacent bits |
| Finite enum | code to String/status | only a documented bijection | reject unknown/reserved command labels |
| Percent or position | raw range to percent | yes for documented target setpoints | distinct actual-feedback and target channels; clamp/round policy |
| Threshold/alarm | scalar or bitfield to alarm | no | one-way derivation; use a documented reset/ack command separately |
| Counter/energy | numeric total to quantity | no | telemetry only; reset is a separately modeled protected operation |
| Date/BCD/packed fields | raw bytes to specialized state | no generic support | explicit documented layout, epoch, timezone, and inverse if writable |

Arbitrary transformations remain possible as user-owned scripts, but they are not poller2 convenience mappings. A script used bidirectionally must preserve and convert quantity units; parsing a numeric prefix is not sufficient.

## Real openHAB verification backlog

| ID | Acceptance check | Status |
|---|---|---|
| OH-UI-01 | YAML `Number:Power` channel is visible in Main UI through its generated effective-runtime ChannelType. | passed manually |
| OH-UI-02 | Main UI creates a compatible `Number:Power` Item and direct default link. | passed manually |
| OH-UI-03 | A managed poller2 Thing uses Main UI's Add Channel picker to create the advertised generated `Number:Power` channel, then validates its required Modbus configuration. | pending |
| OH-UOM-01 | Same raw `42` linked directly to `W` and `MW` Items proves Item metadata supplies an unqualified unit. | passed manually |
| OH-UOM-02 | Display pattern converts stored `42 MW` to `42000.000 kW` without changing protocol semantics. | passed manually |
| OH-PROFILE-01 | Main UI profile list for the dynamic `Number:Power` channel is captured. | passed manually |
| OH-PROFILE-02 | ECMAScript profile returns explicit unit-bearing state on a second live responder value. | passed manually |
| OH-CMD-01 | Capture exact handler command objects for bare and explicit compatible quantity commands. | pending |
| OH-CMD-02 | Implement and verify one complete writable quantity register with W/kW/MW commands, rounding, overflow, and device confirmation. | pending |
| OH-CMD-03 | Verify a rejected command produces no Modbus write and leaves cache/state coherent. | pending |
| OH-CMD-04 | Verify partial register-bit overlay behavior with cache missing, valid cache, write failure, and mismatching next poll. | pending |
| OH-ACTION-01 | Verify typed same-poller write action validation, serialization, result reporting, and recovery. | pending |

## Historical-design research queue

- Inspect `origin/modbus-14058` for the prior OH4 dynamic-channel/write design and identify what was proven versus merely worked around.
- Maintain the use-case-to-architecture mapping in `WRITE_DESIGN_MAPPING.md` as research and implementation evolve.
- Inspect Core command parsing and direct channel-link routing for dimensioned Number Items.
- Research and classify common Modbus engineering conversions into read-only, reversible, stateful, and unsafe/ambiguous categories.
- Convert outcomes into explicit poller2 configuration/API proposals before implementing writes.
