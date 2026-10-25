# Legacy Poller Migration

`poller2` owns channels on its poller bridge.
Migrating the legacy `poller` + `data` topology is opt-in and review-first.
The legacy configuration remains supported until you explicitly remove it.

> The commands below are openHAB console commands, not Main UI controls.
> This documentation does not claim a Main UI migration wizard or automated screenshot coverage.

## Before you start

1. Back up or export the current Thing and Item/link configuration using your normal openHAB backup process. Retain a copy outside the runtime.
1. Record the current legacy Thing UIDs, linked Items, values, and any known device commands so you can compare them after migration.
1. Read the [legacy-only reference](legacy-poller.md) and identify all read transformations, link profiles, and write configuration.
   Automatic migration is limited to qualified read-only contracts.
   Write-configured Things, write diagnostics, JSON write transforms, arbitrary transformation chains, and unknown profiles remain manual work.

## Preview and review

At the openHAB console, run:

```text
openhab> modbus migrate preview
```

The command prints a preview identity, proposed YAML, and `MANUAL:` notices.
It captures an immutable journal review without creating, deleting, or relinking Things or Item links.
Save the complete console output with the backup.
Review every target poller, channel, and replacement link, and resolve every `MANUAL:` entry yourself.
Do not change the legacy source configuration between preview and apply.

## Provider-owned file proposals

Administrator clients can read `GET /rest/modbus/migration/provider-previews`.
This returns a detached proposal without writing the migration journal, Things, or links.
Eligible file-backed mappings include unlinked native YAML candidates, source/target link instructions, safe provider implementation names, and revision digests.
The public provider API does not expose a source filename, so the proposal explicitly reports `UNKNOWN_MANUAL_LOCATION` rather than inventing a path.
Unknown ownership, unsupported conversions, unqualified transformations, and unqualified profile configurations remain manual work.
A proposal may cover only part of a legacy poller's consumers.
Keep that legacy poller active until **every** residual consumer is resolved; migrating the eligible subset does not authorize whole-poller shutdown.
These proposals are not managed workflow groups and cannot be applied or cut over through the managed migration operations.
Review and edit the owning configuration yourself; retain its original contents for exact restoration.
The preview is not a backup or proof that generated files have been loaded by the native YAML provider.
The add-on-hosted view at `/modbus/migration/` displays these proposals in a separate **Read-only provider-owned file proposals · manual review** section.
Use **Load / refetch file proposals** with your existing Main UI administrator session.
It displays candidate YAML as plain text, with copy and download controls, provider ownership, configuration/revision digests, manual link instructions, safe source profiles, and omitted/manual work.
The download uses a generic candidate filename, not an inferred source filename.
It offers no staging or cutover controls for file proposals and never writes the owning configuration.
A failed provider read is shown separately and does not make managed workflow operations depend on provider proposal availability.
Logout or session replacement clears provider review data and discards late responses.

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

Validation uses binding-owned observations from fresh device polls, not Item caches or cached `REFRESH` delivery.
The candidate is the proposed unlinked `poller2` Thing running alongside the legacy topology.
The console identifies it as **Thing ID** and prints every planned channel comparison: source/target channel, address, value type, source/target value, source/target observation timestamp, outcome and reason.
Missing, failed, stale or invalidated evidence is `UNAVAILABLE` with a reason; it does not establish a match and blocks cutover.
There is no force flag, acknowledgement bypass, or automatic link switch in apply.
Comparisons assess read-equivalence, not device write behavior; writes require separate controlled verification.
Automated fixture coverage is not a claim that your live device or every address boundary has been validated.

`POST /rest/modbus/migration/workflows/<preview-identity>/validate` with `{"sourcePollerUid":"<legacy-poller-uid>"}` returns the detailed evidence in `observation.comparisons`.
Each comparison contains `sourceChannelUid`, `targetChannelUid`, `address`, `valueType`, `sourceValue`, `targetValue`, `sourceObservedAt`, `targetObservedAt`, `outcome` and `reason`.
REST timestamps are ISO-8601 strings; internal lifecycle tokens are not exposed.
If freshness checks return aggregate-only `UNAVAILABLE`, both adapters list the reviewed planned channels with unavailable values and an explicit no-evidence reason.
The epoch timestamp `1970-01-01T00:00:00Z` marks absent evidence, not a device reading; these presentation placeholders never authorize cutover.
`GET /rest/modbus/migration/workflows/<preview-identity>` is read-only: it neither collects observations nor persists validation evidence and does not return the POST-only details.

The hosted view includes a **Detailed channel comparisons** section rendering those fields from the validation response.
The REST schema is exercised through Core's actual JSON media provider, and console tests execute the typed validation command for all three outcomes.
Hosted renderer tests use DTO fixtures; they are not proof of a live browser session against this backend.
Validation details are transient: explicit workflow refetch and logout clear them, and they do not override backend cutover readiness.

Explicit cutover removes each eligible legacy Item-channel link **before** adding its reviewed replacement.
Eligible links preserve their complete qualified profile configuration.
Empty or exact `system:default` links, bounded `modbus:gainOffset` settings, JS profiles referencing qualified script files/configuration UIDs, and MAP profiles referencing qualified map files are supported.
Unknown profile parameters, inline scripts, arbitrary profile types, and rule/widget rewrites remain manual.
Preserving a profile reference does not copy or rewrite the transformation resource; keep its owning provider and resource available.
The switch is per poller, and legacy Things remain online.
Source configuration drift or an existing target link refuses the operation.
A partial switch is compensated; failed compensation retains a Core storage journal for explicit revert or rollback, including after a component/runtime restart.

## Verify live operation

With both configurations still available:

1. Confirm each new `poller2` Thing is online and has the expected channels.
1. Compare representative live values with the retained legacy Item values, including address boundaries and byte/word order where applicable.
1. Exercise a read failure and recovery.
   Generated migration channels retain the last Item value, while missing or failed validation evidence remains `UNAVAILABLE`.
   Do not issue device commands as part of read-only migration acceptance.
   Any separately designed manual write migration requires its own controlled device verification.
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

### Replacement-link ownership and restart recovery

Each newly created managed replacement retains the exact qualified source profile configuration and adds an opaque per-link `modbusMigrationOwner` configuration value.
A source with implicit default configuration receives an explicit `system:default` on the replacement; undo restores the original empty configuration.
The reviewed plan identity, manifest and recovery intent include this exact target configuration separately from the original source configuration.
Eligibility is determined by the bounded profile contract, not the presence of a marker.
A source carrying unqualified extra configuration, including an existing migration marker, is not automatically eligible.
Source configuration is never decorated or rewritten, and provider-owned files are never modified.

Recovery distinguishes intent to remove a source, checked source removal, attempted target addition and confirmed target addition.
A removal-only intent cannot claim a target that appeared before the migration added one.
An attempted-add record is also insufficient after reconstruction: it was written **before** the provider add, so a present target may belong to a listener or another actor even if every marker and setting matches.
Automatic deletion after reconstruction therefore requires a confirmed addition; an unconfirmed present target and its recovery record remain for manual review, without restoring an absent source beside it.
During a live switch, callback replacements are checked by exact object instance and authoritative managed provider.
An actual live addition can still be compensated using the exact instance this operation added.
When restoring multiple links, all restored links and their recovery records remain guarded through subsequent provider callbacks and the final completion write.
If a later callback recreates a target, recovery retains the unresolved records and compensates only exact unchanged source instances it added, never existing sources or listener replacements.
After a completed switch or reconstruction, undo requires the reviewed marker, exact expected profile and entire configuration, the authoritative expected managed link provider, and the journaled candidate Thing owned by the same review.
An unchanged editor save retaining those fields preserves the migration claim; retaining the marker while changing the profile does not.

Missing or mismatched markers, changed profile/configuration/provider, unknown record versions/phases, and ambiguous historical unmarked records refuse automatic recovery.
The unresolved record is retained for manual review: inspect both connections and their owning providers before resolving the conflict and retrying.
Recovery never restores a source beside a target it cannot claim and never automatically adopts an unmarked historical connection.
An already-restored exact managed source with no target can be acknowledged without adding it again.

The marker is **not a secret or authentication mechanism** and grants no access.
A deliberately copied identical marker, Item/channel identity and full configuration within the same authoritative managed provider are indistinguishable after reconstruction.
Detecting that kind of clone would require a stronger Core revision/metadata contract.
Standalone runtime-local appliers learn their managed owner during cutover; durable service recovery uses the injected managed link provider and authoritative Thing registry instead of guessing an owner.

### Reviews created before this read-only contract

Reviews created under an older safety contract are `QUARANTINED` for apply, validation and cutover.
A successful fresh read cannot authorize an old target lacking the reviewed legacy-compatible failure policy.
Capture and review a new proposal instead of upgrading or editing the stored old target in place.

Known historical reviews retain their original ownership and recovery records.
Use the console revert or rollback commands below with the saved identity and original Item/source-channel selection; the backend still verifies exact ownership before recovery.
Quarantined reports withhold configuration artifacts, and the hosted view does not offer automatic cutover for them.
Unknown future record versions, corrupt records and ambiguous ownership require manual recovery rather than guessed schema conversion.
Recovery must not promote an old review to the current safety version, including after subsequent reconstruction.

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

The migration plans eligible polled read-only contracts only.
It maps exact source channels independently, including multiple qualified value/read-diagnostic links on one data Thing.
Register-backed Number/Switch/Contact and coil/discrete bit contracts are qualified separately; bit-backed Number channels preserve numeric 0/1 states.
A bounded single Thing-level MAP transformation is preserved before channel type conversion, including scalar and single-element-list configuration forms.
Arbitrary Thing-level scripts/chains, transformed coil/discrete contracts, write configuration, write diagnostics, and direct-endpoint write-only topology remain manual.
Generated channels explicitly use `errorPolicy=keepLast` to preserve legacy read-failure retention; ordinary poller2 channels retain their separately configured/default failure policy.
Validation requires fresh request-bound decoded inputs as well as equal final values.
A transform failure is unavailable evidence; intentionally transformed UNDEF can agree only when both successful reads also have equal information-bearing decoded inputs.
Read diagnostic validation compares event contracts rather than requiring wall-clock timestamps from separate polls to be identical.
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
Full candidate configuration validation and automatic provider-owned file mutation are not implemented.
Both managed preview YAML and provider-owned proposal YAML describe unlinked candidates.
The link manifest/instructions are separate; importing YAML alone does not switch Items or establish managed journal ownership.
