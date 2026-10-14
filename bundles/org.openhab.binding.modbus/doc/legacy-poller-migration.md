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

## Provider-owned file proposals

Administrator clients can read `GET /rest/modbus/migration/provider-previews`.
This returns a detached proposal without writing the migration journal, Things, or links.
Eligible file-backed mappings include unlinked native YAML candidates, source/target link instructions, safe provider implementation names, and revision digests.
The public provider API does not expose a source filename, so the proposal explicitly reports `UNKNOWN_MANUAL_LOCATION` rather than inventing a path.
Unknown ownership, unsupported conversions, transformations, and non-default profiles remain manual work.
These proposals are not managed workflow groups and cannot be applied or cut over through the managed migration operations.
Review and edit the owning configuration yourself; retain its original contents for exact restoration.
The preview is not a backup or proof that generated files have been loaded by the native YAML provider.
The hosted view does not yet display these file proposals.

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
A partial switch is compensated; failed compensation retains a Core storage journal for explicit revert or rollback, including after a component/runtime restart.

## Verify live operation

With both configurations still available:

1. Confirm each new `poller2` Thing is online and has the expected channels.
1. Compare representative live values with the retained legacy Item values, including address boundaries and byte/word order where applicable.
1. For commandable migrated channels, perform a controlled command and verify the physical device and the next poll result.
   Do not assume action acceptance means that a device write succeeded.
1. Check logs and Thing status for configuration or communication errors before considering the migration accepted.

## Revert one link, roll back, or clean up

Restore one exact reviewed Item-channel link independently of the other links in its poller group:

```text
openhab> modbus migrate revert <preview-identity> <item-name> <original-source-channel-uid>
```

Revert removes only that link's migration-owned replacement and restores the original source link with its exact reviewed profile configuration.
Other Items and links, staged candidate Things, and online legacy Things are retained.
It works without candidate validation, even when validation is `UNAVAILABLE` or a partial cutover needs recovery.
A link changed since migration is refused instead of overwritten; correct the conflict explicitly and retry.
Repeated revert is harmless once its link recovery record has been resolved.

### Roll back the complete staged migration

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
The console requires the official Core `StorageService` and stores its versioned journal in `modbus-migration-journal`.
Before provider mutation it stores an immutable reviewed source revision/configuration, original links/profiles, target manifest, and write-ahead ownership/link intent.
Outcomes, staging timestamps, remaining compensation work, and switched link identities are reloaded on component/runtime restart.
An interrupted stage is recovery-only: retry rollback with its original identity before applying again.
Rollback will not remove pre-existing or replacement Things with unknown ownership, and it never deletes legacy Things.

Core's public `Storage` API supports `put`/`get`, but has no synchronous flush or transaction API.
The journal writes and reads back each record before mutation; physical durability follows the selected Core storage backend.
The standard JSON backend defers disk commits, so abrupt process termination or power loss before its commit may lose recent records.
Normal component/runtime restart recovery is supported, but a crash-consistent transactional migration is **not** claimed.
Retain the independent configuration backup and saved preview identity for that failure boundary.
Full candidate configuration validation and provider-owned file patches are not implemented.
Generated preview YAML describes the eventual linked configuration; importing it directly is not the unlinked console staging operation.
