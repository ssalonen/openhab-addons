# Legacy Poller Migration

`poller2` owns channels on its poller bridge.
Migrating the legacy `poller` + `data` topology is opt-in and review-first.
The legacy configuration remains supported until you explicitly remove it.

> The commands below are openHAB console commands, not Main UI controls.
> This documentation does not claim a Main UI migration wizard or automated screenshot coverage.

## Before you start

1. Back up or export the current Thing and Item/link configuration using your normal openHAB backup process. Retain a copy outside the runtime.
1. Record the current legacy Thing UIDs, linked Items, values, and any known device commands so you can compare them after migration.
1. Read the [legacy-only reference](legacy-poller.md) and identify custom `readTransform`, all write configuration, and JSON-producing write transforms.
   The migration deliberately leaves these for manual work.

## Preview and review

At the openHAB console, run:

```text
openhab> modbus migrate preview
```

The command prints a preview identity, proposed YAML, and `MANUAL:` notices.
It only reads the legacy configuration; it does not create, delete, or relink anything.
Save the complete console output with the backup.
Review every target poller, channel, and replacement link, and resolve every `MANUAL:` entry yourself.
Do not change the legacy source configuration between preview and apply.

## Stage unlinked candidates

Apply exactly the identity returned by the reviewed preview:

```text
openhab> modbus migrate apply <preview-identity>
```

Apply stages the reviewed managed target Things/channels **without any production Item links**.
It does **not** delete legacy Things or legacy links.
It can refuse an unknown or stale identity; when that happens, generate a new preview and repeat review rather than substituting an identity.

## Validate and explicitly cut over one poller

```text
openhab> modbus migrate validate <preview-identity> <legacy-poller-uid>
openhab> modbus migrate cutover <preview-identity> <legacy-poller-uid>
```

Validation is a separate dependency seam, not a comparison of Item caches.
In this slice the console has no binding-owned observation provider, so validation reports `UNAVAILABLE` and cutover is blocked.
There is no force flag, acknowledgement bypass, or automatic link switch in apply.
An observation integration must compare all planned channels from fresh post-create device polls, verify candidate configuration/lifecycle, and report `MATCH`, `MISMATCH`, or `UNAVAILABLE` with the reviewed identity, poller UID, and timestamp.
No live-device validation or boundary-coverage claim is made here.

When that integration is available, explicit cutover removes each eligible legacy Item-channel link **before** adding its reviewed replacement.
Only empty or exactly `system:default` profile configurations are eligible; other profiles and rule/widget rewrites remain manual.
The switch is per poller, and legacy Things remain online.
Source configuration drift or an existing target link refuses the operation.
A partial switch is compensated; a failed compensation retains an in-memory journal for retry through rollback.

## Verify live operation

With both configurations still available:

1. Confirm each new `poller2` Thing is online and has the expected channels.
1. Compare representative live values with the retained legacy Item values, including address boundaries and byte/word order where applicable.
1. For commandable migrated channels, perform a controlled command and verify the physical device and the next poll result.
   Do not assume action acceptance means that a device write succeeded.
1. Check logs and Thing status for configuration or communication errors before considering the migration accepted.

## Roll back or clean up

If verification fails, restore switched legacy links and remove only migration-owned candidates:

```text
openhab> modbus migrate rollback <preview-identity>
```

Before cutover, rollback leaves every legacy Thing and link untouched.
After cutover, it removes owned replacement links before restoring the reviewed legacy links and their eligible profiles.
If rollback fails, retry the same command before any further migration action.

There is no `cleanupLegacy` console command.
After successful live verification, disable legacy polling before considering separate deletion through your normal managed-configuration process.
Retain the backup for restoration.
Treat cleanup as a separate, irreversible change: export again immediately before it, remove only the legacy resources you reviewed, then verify the surviving `poller2` configuration once more.

## Scope and limitations

The migration plans safe legacy read topology only.
It does not automatically convert custom read transforms, write configuration, or JSON-producing write transforms; those appear as manual work and are excluded from generated YAML and apply.
It never removes legacy resources during preview or apply.
Preview identities, candidate ownership, and compensation journals are **in memory only** and are lost on console component/runtime restart.
Durable manifests, restart recovery, independent per-channel revert, full candidate configuration validation, and provider-owned file patches are not implemented.
Do not rely on console rollback after restart; use your external configuration backup.
Generated preview YAML describes the eventual linked configuration; importing it directly is not the unlinked console staging operation.
