# Block-state compatibility

The modern adapters (1.16.5, 1.18.2 and 1.20.1) use
`src/shared/java/dev/epoch/bridge/BlockStateCompatibility.java` when importing a
snapshot palette. `CompatibilityRules` loads built-in JSON rules, external packs and Java providers. The legacy metadata adapters retain their existing mappings and now expose registry audits. See [the architecture plan](COMPATIBILITY_ARCHITECTURE.md) and [mod integration guide](MOD_COMPATIBILITY.md).

Each modern adapter supplies the actual destination registry's property names
and allowed values. The shared layer accepts directly representable states first. Otherwise it copies
the source properties, selects one explicit destination-version rule, then validates every resulting property.
Compatible states are built on the destination block's default state, as before.
Unspecified properties retain their destination defaults. Missing blocks,
unmapped legacy payloads, unsupported properties and unsupported values are
rejected. The existing unknown-block path retains the original, unmodified SNBT
and block-entity data for restoration.

## Built-in rules

`epoch:dry_leaves_before_waterlogging` applies only to vanilla oak, spruce, birch,
jungle, acacia and dark oak leaves entering 1.16.5 or 1.18.2, when the destination
does not expose `waterlogged`. It removes only `waterlogged=false`. It retains
`distance` and `persistent`, which still undergo registry validation. On return
to 1.20.1, its default `waterlogged=false` reconstructs the dry state.

`waterlogged=true` is not converted. There is no generic property stripping,
suffix matching for mod blocks, or lossy conversion with separate restoration
metadata. Existing placeholders containing dry leaves also pass through this
rule at the next import because their original SNBT is exported unchanged.
The rule deliberately uses the destination version, rather than assuming the
snapshot exporter is the version that created every state.

The pack also safely drops `waterlogged=false` on the four vanilla rail types
when targeting 1.16.5. Wet rails remain preserved. Empty cauldrons and water
levels 1–3 are mapped across the cauldron/water_cauldron split; lava and powder
snow are not substituted with water.

The built-in pack also maps `dirt_path` to `grass_path` for 1.16.5 and the reverse
for 1.18.2/1.20.1. Legacy skull entries using Mojang's `%%FILTER_ME%%` sentinel
are no longer exported as real block IDs; they retain raw legacy state.

## Diagnostics

Each modern import replaces `diagnostics.blockStateCompatibility` in the IPC
status JSON with a report for that snapshot's palette:

- `sourceVersion`, `targetVersion`: exporting and importing client versions.
- `exactStates`, `convertedStates`, `rejectedStates`: counts of distinct palette
  entries, not world block positions.
- `failureCounts`: totals for `missing_block`, `legacy_payload`,
  `unsupported_property`, `unsupported_value`, `invalid_block_id`, `rule_conflict`,
  `invalid_rule_output`, and `unmapped_rule_value`.
- `examples`: up to 32 converted or rejected entries in palette order, including
  `paletteIndex`, `block`, `outcome`, applied `rules`, and, on rejection, `reason`
  plus `property`/`value` where relevant.
- `omittedExamples`: additional entries beyond the example limit. Totals remain
  complete even when examples are omitted.

The report describes state decoding, not import completion. Block-entity/NBT
compatibility is checked separately and may still require placeholders.
`diagnostics.compatibilityRules` identifies the loaded rule set and SHA-256.
`diagnostics.blockEntityCompatibility` reports rejected payload positions and up
to 32 state/reason examples separately.
`diagnostics.unknownBlocks` remains the total number of placeholder positions
from the completed import.
