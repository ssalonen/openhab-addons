# Read-only migration conformance inventory

`readonly-conformance.json` is a synthetic inventory for the bounded read-only milestone.
All identifiers, endpoint addresses, labels and transformation references are synthetic.
It contains no uploaded scripts, original identifiers, credentials or private labels.

The inventory contains 37 Things: one TCP endpoint, two legacy pollers and 34 data Things.
There are 40 associated Items and 40 links.
The source-channel accepted type is recorded independently of the linked Item type.
In particular, a DateTime source with a MAP profile can be linked to a Switch Item.
Unlinked legacy channel types are retained and must not become migration blockers for unrelated linked contracts.

The exact expected source partitions are recorded as identifier sets, not just totals.
They contain 16 inherently read-only Things with 18 links, 11 read/write Things with 13 links, and seven intentionally write-only Things with nine links.
Four write-only Things have a direct TCP parent.
The 22 links on write-configured Things must remain manual in this milestone.
Do not add these overlapping topology/profile/channel classifications as if they were disjoint.

Transformation references are fixture contracts, not executable code in this inventory.
Tests that invoke transformation services must supply isolated, information-bearing implementations under the synthetic references.
Use the same reference on the source and target link, then verify actual transformed Item state through Core's profile service.
Do not substitute identity scripts and claim non-identity transformation coverage.
Keep transport failures distinct from intentional transformed UNDEF.

A fixture count test is not a migration acceptance test.
Acceptance must exercise the actual adapter/planner and assert the exact 18 selected links, all 22 write-configured refusals, exact profile restoration, and no device writes.
Fresh-read evidence, handler/configuration replacement refusals, isolated cutover and normal-restart undo remain separate gates.
