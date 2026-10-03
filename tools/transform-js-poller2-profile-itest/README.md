# Poller2 `transform:JS` disposable-runtime experiment

This experiment is isolated from the Modbus OSGi integration bundle. It starts:

1. a local Modbus-TCP responder returning holding register `42`;
1. an ARM64 openHAB 5.3 snapshot container;
1. freshly built poller2 and Core Modbus-transport JARs;
1. YAML containing a `Number:Power` poller2 channel and a `transform:JS` Item link.

The intended assertion is:

```text
42 raw register -> Number(input) / 10 + ' W' -> 4.2 W Item state
```

Run from the add-ons checkout:

```text
./tools/transform-js-poller2-profile-itest/run-disposable.sh
```

Prerequisites are the built binding JAR and the Core Modbus-transport JAR. Override the Core checkout with `OPENHAB_CORE=/path/to/openhab-core`.

## Verified runtime result

A real ARM64 openHAB 5.3 snapshot runtime accepted the YAML, activated the local Modbus transport and poller2 handler, and updated the linked Item through the real Core link/profile route.

The responder was changed live from register `42` to `52`; the REST Item-state endpoint changed from `4.2 W` to:

```text
5.2 W
```

That is end-to-end evidence for:

```text
Modbus register → poller2 DecimalType → CommunicationManager → transform:JS → Number:Power Item
```

The Docker snapshot's official JSScripting feature installation is asynchronous. A locally deployed JSScripting JAR from this checkout also emitted self-classloader `ClassCastException` diagnostics because its build revision differed from the snapshot Core revision. The verified Item update occurred despite those diagnostics, but the clean repeatable gate should use a distribution built from the same Core/add-ons revision or install the distribution's matching JSScripting feature before the poller starts.

## Main UI link and UoM reference results

The following checks were performed in the authenticated visible Main UI against the live poller2 channel. The YAML source remained:

```yaml
channels:
  power:
    itemType: Number
    itemDimension: Power
    config:
      address: "100"
      valueType: uint16
```

For current Main UI compatibility, the effective runtime channel was assigned a generated `Number:Power` ChannelType. This does not change the YAML declaration or the raw Modbus value; it only lets Main UI render the channel and open the standard link flow.

The checked-in evidence image [`evidence/main-ui-poller2-online.png`](evidence/main-ui-poller2-online.png) captures the authenticated Main UI Things list with `modbus:poller2:profile-experiment` ONLINE. Its SHA-256 is `ab1c54f03370efea6f6b07cbafeff9cbc2e44004713a257250d8924be83bf407`; reproduce the visible-runtime capture after a clean deploy and compare its digest before accepting UI changes.

### Direct compatible links without a profile

The same raw register value, `42`, was linked through Main UI with the `Default` profile to two newly created `Number:Power` Items:

```text
Item unit metadata     displayed/stored Item state
------------------     ---------------------------
W                      42 W
MW                     42 MW
```

A raw binding `DecimalType(42)` is therefore wrapped in the linked Item's configured/default unit. Core does not infer that `42` physically means watts, and it does not convert it to megawatts merely because the Item uses `MW`.

### Display conversion is separate

A third direct Item used:

```text
Unit metadata:             MW
State description pattern: %.3f kW
```

Main UI displayed:

```text
42000.000 kW
```

This is correct presentation conversion of the stored quantity `42 MW`. A state-description display pattern does not change the binding value, the Item's stored unit, or command semantics.

### Profile choices observed in Main UI

For the `Number:Power` channel, Main UI offered:

```text
Default
Follow
Gain-Offset Correction
Hysteresis
Offset
Range
SCRIPT ECMAScript (ECMAScript 262 Edition 11)
SCRIPT Rule DSL (v1)
Timestamp on Change
Timestamp on Update
```

`SCRIPT ECMAScript` is the UI-visible JSScripting profile. The YAML fixture's JavaScript state-side script returned an explicit quantity and produced `4.2 W` from raw `42`.

### Commands are not implemented by poller2 yet

An authenticated openHAB command of `1 MW` sent to the direct `Number:Power` Item returned `HTTP 200 OK`, but the Item remained `42 MW` after the next poll and the responder received no Modbus write. This is expected: `ModbusPoller2ThingHandler` currently inherits `ModbusPollerThingHandler.handleCommand()`, whose implementation is explicitly a no-op.

This experiment does not prove reverse unit conversion. A future writable poller2 channel must implement command handling and then be tested with commands expressed in W, kW, and MW before register encoding is considered correct.
