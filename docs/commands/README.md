# Command contract

[`commands.json`](commands.json) is the library-authoritative dictionary of command names,
descriptions, input types, permitted API values and model/dialect variations.
[`commands.schema.json`](commands.schema.json) describes its format. Gradle generates
portable Kotlin descriptors from this dictionary; no JSON parser is required at runtime.

Migration instructions and maintainer follow-ups live separately in
[`command-actions.json`](command-actions.json), keyed by `[protocolId][commandId]`.
Each entry contains follow-ups such as `remap_client`, `integrate_client`, `review_library`
or, once verified, `implement_library`. These flags neither enable commands nor create
issues automatically. The file is not a command inventory: an absent entry means no
migration follow-up is recorded, not that the command is unsupported or missing.
`_unattributed` holds commands whose protocol attribution still needs investigation.

## Client API

Use the selected protocol's `getCommandDescriptors()` to discover commands, then
`encodeCommand(id, value)` for checked encoding. Android clients use
`EucBleClient.getCommandDescriptors()` and `sendCommand(id: String, value: CommandValue)`;
rejections use the existing error callback.

```kotlin
val descriptor = protocol.getCommandDescriptors().first { it.id == "SET_LIGHT_MODE" }
val choices = (descriptor.input as CommandInput.Choice).options
val on = choices.first { it.id == "on" }
val result = protocol.encodeCommand(descriptor.id, CommandValue.Integer(on.value))
```

Render only input kinds and operations the client understands. Refresh discovery after
model/dialect changes and reconnects. Encoding rechecks the current descriptor and live
prerequisites; discovery is not a promise that a later command will succeed.
An encoded or queued write is not wheel acceptance or confirmation.

The existing enum-based raw encoder and legacy send API remain compatible, including
their historical clamping. The checked API instead rejects invalid types, out-of-range
values, unlisted choices and extra tuple fields **before** invoking an encoder.
KingSong coupled speed/alarm writes additionally require the companion settings to have
been read, rather than silently filling unknown settings with zero.

## Dictionary

Commands have `name`, `description`, `encoding` and `input`. Optional `operation` describes
behavior independently of input shape, for example `file_transfer` with `none` input.
Encoding is `available`, `conditional`, `unavailable` or `unknown`; authentication,
challenge and transfer state remain in Kotlin, not in a configuration rules engine.

Input kinds are `none`, `boolean`, `range`, `choice`, `text`, `tuple`, `record` and
`raw_payload`. Ranges have inclusive integer bounds and a step in public API units;
`scale` converts one API integer into the named physical unit. Choices separate semantic
IDs from integer wire-API values. Labels such as `mode_0` deliberately preserve unknown
semantics. Records use typed values such as `PasswordChangeRequest`, not an untyped UI map.

**Encoding bounds are not safe hardware limits.** `hardwareLimits: "unknown"` explicitly
preserves that distinction. Do not treat a maximum representable value as a recommended
operating setting. Missing commands mean no implementation is advertised, not that the
hardware cannot support them.

Profiles match exact decoded `model` strings and implementation dialect names, not BLE
advertisement names. All specified selectors must match; missing identity never matches
a required selector. At most one profile may match, and it replaces each listed command
descriptor in full. There is no nested merge or file-order priority. Firmware selectors
are intentionally absent until verified firmware boundaries exist.

## Extension and maintenance

Command IDs, operations and input-kind names are open strings. Future IDs do not require
an enum or schema change. Unknown input kinds preserve their metadata but checked encoding
rejects them until a dedicated library handler exists; clients must not guess values or
route them through `CUSTOM`. Custom implementations can supply a `CommandInput` validator
and override the string-ID encoder. Additional declarative metadata belongs in `extensions`.
Breaking changes to existing meanings require a new schema version.

Keep client drift out of the dictionary: remap clients to library values in the actions
file. Alleged missing commands start as `review_library`; verified gaps can become
`implement_library` alongside encoder and test work. A client control alone is not proof
of hardware support.

Validate changes against the JSON Schema. The build also checks domains and profile
overlaps. Portable contract tests consume the generated dictionary, enumerate finite
choices and small ranges, and exercise boundaries for wider ranges. Independent wire
goldens remain separate to catch incorrect catalogue or encoder values.
