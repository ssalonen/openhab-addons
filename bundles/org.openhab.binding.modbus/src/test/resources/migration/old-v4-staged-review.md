# Historical reviewed-plan fixture

`old-v4-staged-review.json` was produced by compiling the unmodified migration planner, journal and DTO sources retrieved with `git show 9a5b8f90317bf8f0989b2c48241472b4a57ac6a5:<path>` (not the current planner), then serializing its `MigrationJournal.Workflow` with Gson 2.13.2. All identifiers are synthetic.

Inputs:

```java
var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
        Map.of("start", 100, "length", 2, "type", "holding")));
var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
        Map.of("readStart", "100", "readValueType", "int16"),
        List.of(new LegacyLink("Temperature", "modbus:data:tcp:plant:temperature:number", "Number",
                Map.of("profile", "system:default")))));
var preview = new ModbusMigrationPlanner().preview(pollers, data);
var record = new MigrationJournal.Workflow(preview, pollers, data, "STAGED", "2026-01-01T00:00:00Z",
        List.of(), List.of());
System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(record));
```

This is an actual pre-contract reviewed target: preview-v4 identity, reviewSafetyVersion=1, no sourceChannelUid field, and no explicit errorPolicy=keepLast. Replanning its source snapshots under new code must not authorize its stored target. Tests vary only workflow outcome and switched/recovery state; the exact stored preview, link ownership marker and source profile remain unchanged for recovery.

The fixture describes normal journal reconstruction and does not assert physical-storage flush or abrupt-power-loss guarantees.
