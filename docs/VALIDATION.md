# Epoch 0.2 — validation status, 2026-10-01

## 1.20.1 to 1.7.10 native save memory pressure, 2026-10-05

The 21:34:37 (Asia/Shanghai) handoff completed in 9,460 ms. At 21:35:42,
the 1.7.10 integrated server reported `OutOfMemoryError: GC overhead limit exceeded`
with `-Xmx3G`; the native File IO Thread also failed while compressing Anvil NBT.
The last status recorded 1,221 resident chunks and 70,914 placeholders in the
handoff region. A read-only scan found 5,364,157 saved placeholder records across
2,149 chunks in the target save; this is an on-disk total, not a live heap count.
The original logs, crash report, status and counts are retained under
`build/oom-1710-20261005/`. No live heap dump was available after manual exit.

Vanilla 1.7.10 queues uncompressed chunk NBT without a capacity limit. Dense
placeholder saves can therefore retain far more memory than their compressed
region files suggest. The streaming transformer now waits for fewer than eight
pending native saves before serializing another chunk, using the loader's own
queue lock and existing IO worker. A stalled or interrupted writer stops the
world instead of silently discarding saves. Status now includes native save wait
time, heap use and the resident tile count. Existing NBT and save formats are
unchanged.

`nativeSaveQueueTest` exercises the transformed native serializer, File IO Thread
and region reader without launching a game/window. With the hook disabled, a
briefly blocked writer accumulated 53 chunks and failed the capacity assertion.
With the fix, the queue peaked at eight and all 128 chunks / 640,000 placeholder
states, payloads and source identities roundtripped with a 256 MiB heap. Timeout
and interruption checks also passed. The full offline 1.7.10 build, legacy,
environment/item and seamless compatibility checks passed. The installed
1.7.10 adapter matches the build by SHA-256; installation backs up the previous
JAR. No user save was changed by this investigation or regression test.

This verifies and fixes unbounded native save accumulation, a likely contributor
to this OOM. It does not establish the incident's full live heap breakdown or
verify a restarted handoff and continued play in the user's original world.

## Adopted-window focus recovery, 2026-10-05

The 18:36:04 (Asia/Shanghai) 1.20.1 -> 1.12.2 handoff completed successfully.
The host remained `READY`, and the target kept publishing heartbeats with
`clientFrozen=false`, `movementInput.inGameFocus=false`,
`movementInput.displayActive=false` and no open game screen. The adopted host
only called `SetFocus` during reveal; unlike its Swing mode, it had no recovery
path when Windows later reactivated the original top-level source HWND. LWJGL 2
updates `Display.isActive()` from `WM_SETFOCUS`/`WM_KILLFOCUS`; activating the
source does not give the embedded target that focus event.

The adopted coordinator now checks focus in its existing 100 ms poll while
`READY` or `PREPARING`. It routes focus to the selected visible child only when
the original game root is already foreground. It checks the target input queue,
HWND ownership, visibility, enabled state and root, leaves menus, move/size,
mouse capture and unrelated controls alone, and rechecks after input attachment.
An already focused child incurs no attachment or key-state reset. The ordinary
source remains unmanaged between handoffs. Confirmed transfers are recorded in
`host.log` and `host-status.json.focusTransfers`.

`tests/test_host_focus.ps1` passed 223 assertions for repeated reactivation,
background/minimized/hidden/disabled windows, modal/menu/drag states, stale HWNDs,
attachment failure, state races, transfer verification and queue cleanup.
All 9 bootstrap tests passed, and `launch_epoch.py --build-only` compiled the
updated host. The focus regression also runs from `scripts/build.ps1`.
All native observations and mutations in the regression are simulated; no
desktop automation or image reading was used. Physical click/Alt-Tab recovery
in Minecraft remains unverified. The running coordinator retains its old loaded
classes; restart the game session to load the updated host. No adapter or native
DLL replacement is required for this host-only fix.

## Empty source-region load failure, 2026-10-05

The 15:30:41 1.16.5 server crash after returning from 1.20.1 reported
`Epoch terrain load`, caused by `IOException: Truncated source region header`
in `NativeRegion.hasFullChunk`. The original source save contained five zero-byte
region files and no nonempty file shorter than the 8192-byte header. Vanilla
1.20.1 opens region files with `CREATE` even when probing absent chunks; an empty
file is a normal state before its first chunk write. Epoch incorrectly rejected
that state. Original logs and build/test evidence are retained under
`build/empty-region-freeze-20261005/`.

The shared reader now treats only a zero-byte file as containing no saved source
terrain. Nonempty partial headers and truncated chunk records still fail closed;
partial-header diagnostics now identify the path and size. No region files need
to be removed or rewritten.

The empty-file regression failed on the original code and passes after the fix,
including subsequent writes to the same file, absent records, full/proto status,
and rejection of partial headers/data. Shared Java tests, all 56 Python tests and
offline builds/checks for all five adapters passed. All installed adapter JARs
match their respective builds by SHA-256.

`scripts/test_native_generation.py --version 1.16.5 --empty-source-regions` passed
in a real client using an isolated source with 25 empty region files: native
terrain generated, simulation advanced, all 25 source files stayed empty, and
the client exited cleanly. Evidence is
`.minecraft/epoch/evidence/native-generation-4d415f1fe4aa492c811e4d57e0d261c6/`.
All 36,935 pre-existing files in the user's five linked saves and shared authority
have unchanged SHA-256 hashes after the isolated test and installation.
This exercises the failing terrain-load path, not an interactive replay of the
user's entire five-version route.

## Repeated return to 1.16.5, 2026-10-05

The user's final 1.18.2 -> 1.16.5 return was followed by a persistence failure:
`Stale chunk revision: 271,47; expected=0, actual=23895`. Original logs and status
are preserved under `build/repeated-switch-20261005/`. All game processes had exited
before investigation, so no live thread dump was available for this incident.

The 1.16.5 adapter now refreshes authority even when asynchronous prefetch found no
chunk, and reconciles terrain plus revision when a delayed FULL chunk registers
after the handoff's resident scan. Stale-write rejection remains enabled.

Offline 1.16.5 build and environment/item checks passed. Real-client evidence:

- `.minecraft/epoch/evidence/streaming-77dc2766649b42c6afd60575b4e105b5/`: forced
  missing-prefetch and late-FULL registration cases both restored canonical terrain;
  existing persistence checks and clean shutdown passed.
- `.minecraft/epoch/evidence/streaming-handoff-7517d49b5bb443b29ec76e3cd2f4d520/`:
  three 1.20.1 <-> 1.16.5 round trips (six handoffs), edits outside the handoff area,
  unknown-block restoration, stale-owner rejection and both clean shutdowns passed.
  Run with `scripts/test_streaming_handoff.py --target-version 1.16.5 --round-trips 3`.

The installed 1.16.5 JAR matches the built artifact by SHA-256. These checks use
isolated saves, not the user's original world or the full four-version route.

## 1.20.1 to 1.7.10 placeholder unload stall, 2026-10-05

Two live thread dumps (`build/hang-1710-threads.txt` and `build/hang-1710-threads-2.txt`)
showed the client in `World.updateEntities` / `ArrayList.removeAll`, while the server
was serializing `Epoch.UnknownTile` during chunk saving. The last handoff status
reported 66,751 unknown blocks. Payload-only 1.7.10 tiles now return `canUpdate=false`
so Forge excludes them from its tick list and its quadratic unload removal scan.
Terrain capture also bypasses encoding the placeholder envelope when its original
state and payload are available directly.

The offline 1.7.10 `build` passed, including a regression exercising Forge's actual
single/bulk registration and unload paths with 66,751 placeholders plus a normal
ticking tile, NBT reload, and description packet creation. Existing legacy and
environment/item tests passed (`build/fix-1710-hang-build.log`). This does not yet
verify a restarted real-client handoff or eliminate the separate cost of saving
large numbers of placeholder entities.

## Minecraft directory relocation

The active game root is now `D:\Files\Epoch\.minecraft`. `assets`, `libraries`, `versions` and `launcher_profiles.json` were moved from the project root. Both real HMCL Forge profiles retain their user worlds and have matching Epoch adapters in their own `mods` directories. All 48 user-save files have identical before/after SHA-256 hashes.

The former `.minecraft/instances` test clients, the test-only `Epoch Transfer` save in the 1.16.5 profile, and stale coordinator state were moved into `.minecraft/epoch/retired-tests-20261001`. Permanent recursive deletion was rejected by automatic approval review; this reversible archive is not scanned as an instance. Discovery now returns exactly the two real `.minecraft/versions` profiles. Installation and normal launcher commands do not recreate the test instances.

The host compiles from the relocated libraries. Shared Java/AutoRuntime checks, 21 Python tests and both native export checks pass. Relocation evidence is `.minecraft/epoch/evidence/relocation-verified.json`. Runtime measurements below describe the earlier layout; games were not launched during this relocation.

Local time zone: Asia/Shanghai. Current v0.2 observations are separated from the earlier v0.1 lab results below. The previous six-cycle measurements do not by themselves verify the new automatic startup/list workflow.

## v0.2 build and isolated tests

- Forge 1.20.1 and 1.16.5 adapters compile and reobfuscate.
- SharedBridge tests pass real gzip/NBT validation and atomic Windows IPC checks.
- AutoRuntime tests pass session isolation, stale-heartbeat rejection, request session tagging and startup-error visibility.
- All **19 Python tests pass**: 14 discovery/deployment checks plus five bootstrap checks covering cross-process locks, Java/Python lock interoperability, rejection of competing sessions and session-specific error cleanup.
- Restart regression passes: an existing IPC shutdown command is ignored by a new client; subsequent commands execute normally without deleting the prior command file.
- Build scripts now run these checks before installation and stop on failure. Installation uses the shared safe installer and refuses running target instances.

## v0.2 real-client results

Real clients run inside `D:\Files\Epoch\.minecraft`; these are not simulated game engines. Session evidence currently includes:

`.minecraft/epoch/evidence/adopted-2f64de63-8768-4ffc-8a7b-2bc870bc2d7a/`

- Automatic adoption of an already-running source and the first target transition were exercised. The first measured final handoff was **1137 ms**; target preparation/cold startup is additional time.
- Source 1.20.1 started with stock GLFW and auto-adopted HWND **4004340**. Removing the target adapter before selection exercised automatic installation of the 1.16.5 adapter and creation of its separate transfer save.
- Final reverse-entry session: `.minecraft/epoch/evidence/adopted-63c90f14-b63d-4d10-8e00-ff4385d6278f/`. Source 1.16.5 started normally with stock GLFW and auto-adopted HWND **11606506**; this exact HWND remained the root across every transition.
- Final `legacy`, `restored`, `edit` and `edited` checks all pass. Both client and server camera remain `(0.5,64,-3.5,0,10)` within tolerance. Cherry planks become an unknown block and restore; bookshelf state and complete written-book NBT restore exactly; chest keeps seven named diamonds. Deleting the old-version placeholder leaves air on return, and stone-to-dirt edits survive. Item-entity count remains zero.
- Four final warm handoffs: **1041 / 1043 / 1249 / 920 ms**. Cold target startup is additional time; these are not cold-start measurements.
- The existing target `Epoch Lab` save's **27 files have identical before/after SHA-256 hashes**. Only the dedicated transfer save was loaded for the destination.
- Actual registered Epoch Key handlers in both versions open their real version-list screens; diagnostic selection then invokes the same selection path used by list buttons. Current-instance identity and catalog contents were checked in the running mods.
- Two test-harness issues were corrected before accepting the final run: repeating fixture setup dropped old container items; starting setup before the 400 ms input-resume delay could override the fixture's teleport. Setup now clears fixture drops and waits for client/server camera agreement. The failed baseline was retained alongside the final evidence. No handoff-camera or duplicate-item assertion was relaxed.

The v0.2 design retains the original source window and prepares destination clients in independent `Epoch Transfer <id>` saves. Destination preparation may take tens of seconds or longer, while source gameplay continues. The supported transfer region remains X/Z `[-32,32)`, Y 0–255, single-player Overworld only.

**Physical mouse right-click, visible window focus, and visual list interaction remain unverified while the Windows desktop is locked.** Diagnostic IPC can invoke real item/list/select handlers, but that does not demonstrate a physical mouse event or establish desktop focus behavior.

## Historical v0.1 verification — 2026-10-01

Tests ran against real local Forge clients inside `D:\Files\Epoch\.minecraft\instances`, not mocked game engines. Local time zone: Asia/Shanghai.

### Runtime

- Minecraft 1.20.1 / installed Forge 47.4.23 / Java 17.0.15.
- Minecraft 1.16.5 / Forge 36.2.42 / Corretto Java 8u492.
- Windows 11, Ryzen 7 7700, 32 GB RAM, RTX 4070 Ti SUPER.
- Java host with a persistent top-level HWND; independent embedded GLFW child windows.
- Patched LWJGL GLFW fork at pinned commit `03f63972b7238a179123f4cb3fa2c2539d5a6dea`; original game libraries remain unchanged.

### Passed

| Check | Observed result |
|---|---|
| Both real clients load the prepared worlds | Successful |
| 1.20.1 → 1.16.5 | First successful measurement: 1185 ms |
| 1.16.5 → 1.20.1 | First return: 930 ms |
| Same top-level window | HWND 2033646 unchanged during the first session; HWND 2360268 unchanged during the restarted session |
| Compatible blocks | Grass, stone/dirt and chest preserved |
| Unsupported block | Cherry planks become `epoch:unknown_block`, then restore |
| Unsupported block entity | Chiseled bookshelf retains complete original block state and block-entity SNBT inside the placeholder |
| Original payload restored | Written book title, author and page text survive; restored bookshelf SNBT equals the original string |
| Compatible container | Seven named diamonds remain in the chest |
| Edits in old version | Deleted placeholder stays air on return; stone changed to dirt stays dirt |
| No duplicate container drops | Live item-entity count remains zero |
| Persistent state | Close on 1.16.5, restart into 1.16.5; edits and unknown block NBT survive; return restores the original bookshelf |
| Actual item handler | Both registered item `use` handlers invoked in real clients, incrementing diagnostic counters and triggering the full host handoff |
| Input state after handoff | Client exits freeze state; selected slot and standing camera are preserved |
| Repeated final-build handoffs | Six item-driven transitions, 935 / 1136 / 794 / 1121 / 899 / 1013 ms |
| Standing camera | After settling, both server and client report position `(0.5,64,-3.5)`, yaw `0`, pitch `10`, within 0.002 tolerance |
| Visual scene | Inspected both actual framebuffer PNGs; near-field block geometry, ground, camera and held item line up. Native fog/horizon still differs. |

Measured handoff times include export, frozen image transfer, import/render readiness and resume acknowledgement. The client deliberately retains input blocking for roughly another 400 ms during focus handover. These are measurements for this small, prewarmed lab on this machine, not cold-start or arbitrary-world guarantees.

### Shared/native checks

- Both Forge jars compile and reobfuscate successfully.
- Shared tests compile with `javac --release 8` and pass real gzip/atomic-file checks: raw Unicode/NBT strings and integer arrays remain unchanged; invalid palette/runs/volume/camera data are rejected; command IDs survive heartbeats; repeated commands are ignored; concurrent Windows readers see complete status files.
- Native symbol verification covers 127 functions for LWJGL 3.2.2 and 135 for 3.3.1.
- Actual Java 8/17 binding smoke checks initialize GLFW, NativeWin32 and NativeWGL classes successfully.
- Bootstrap of the pinned native source archive and application of the patch was exercised independently.

### Fixed during runtime testing

1. Version-specific Nether generator metadata needed the legacy seed field.
2. Windows atomic replacement needed bounded retry when readers hold a file momentarily.
3. Cross-process window embedding exposed GLFW's dereference of another process's window pointer. The native patch resolves only windows owned by the current GLFW instance.
4. Upstream GLFW lacked LWJGL extension exports, so the final native build uses the compatible LWJGL fork.
5. Old container replacement could produce duplicate item drops. Import clears old containers before replacing their block and installs fresh block entities.
6. Camera eye-height conversion produced a tiny below-floor error. Near-integer rounding limited to `1e-6` prevents repeated handoffs from accumulating that error.

### Still unverified or outside scope

**Physical mouse right-click and visible desktop focus behavior remain unverified.** Windows became locked during testing. Desktop input automation was stopped; item handlers were instead exercised through the mod's diagnostic IPC in the real running clients. This verifies the registered item code and handoff pipeline, but does not claim a physical mouse click was tested.

Arbitrary existing saves, infinite terrain, dimensions other than Overworld, entities, pending ticks, incompatible inventory item types, unsupported block-entity schema changes, exclusive fullscreen, other operating systems and other GPUs remain outside this initial prototype's tested scope.

### Evidence

Runtime evidence is saved under `.minecraft/epoch/evidence/`:

- `baseline.json`, `legacy.json`, `restored.json`, `edited.json`.
- `persistence.json` and `cycles.json`.
- `item-1.20.1.json`, `item-1.16.5.json`.
- First-frame comparisons: `1.20.1-before.png`, `1.16.5-before.png`.
- Current frozen frames: `.minecraft/epoch/control/<version>/frozen.png`.

The authoritative event log is `.minecraft/epoch/host.log`. Earlier failed attempts and their crash reports remain available for diagnosis; they are not evidence that the final patched build is failing.


## Carried-item switch regression — 2026-10-04

The local coordinator log and 1.12.2 client log show that the 21:46–21:55 switches from 1.20.1 to 1.18.2, 1.16.5, 1.7.10 and 1.12.2 all rejected `minecraft:shaper_armor_trim_smithing_template`. The target inventory preflight rejected the missing registry entry and the coordinator resumed the source.

The five adapters now carry unavailable inventory and ender-chest items as non-usable `epoch:unavailable_item` envelopes. `ItemTransferTest`, invoked by each adapter's `environmentTest`/`check`, uses each version's real item registry and `ItemStack` save/load implementation to check the template, opaque NBT, repeated transfers, current split counts and moved slots, compatible restoration, empty inventories and invalid/oversized inputs. Container block conversion retains its existing conservative checks.

Validation commands: offline Gradle `build` in all five modules; `tests/test_shared.ps1`; and `python -m unittest discover -s tests -p 'test_*.py'`. Build/test logs and installed-JAR SHA-256 checks are retained locally under `build/item-transfer-fix/`. This regression run uses native headless tests; it does not claim a new interactive window-handoff run.

## 1.7.10 missing terrain regression — 2026-10-05

The authority load hook activated imported chunks without calling vanilla's
`Chunk.populateChunk`. When an imported chunk completed a native 2x2 neighbourhood,
the waiting native neighbour stayed unpopulated; `EntityPlayerMP` refuses to send
chunks until population and lighting finish. The hook now runs vanilla's neighbour
population after registration, keeps imported terrain marked populated, and cancels
queued unloads through the provider's already-loaded path.

The client readiness check now requires the surrounding 3x3 chunks to have received
terrain data before accepting a drained renderer queue. It distinguishes the
client provider's missing-chunk sentinel from actual all-air terrain (legacy
`chunkExists` always returns true). Handoff sends S26 bulk packets, because an S21
full-chunk packet with no sections means unload in this version.

Validation:

- The new real-client native-neighbour regression failed before the fix in
  `.minecraft/epoch/evidence/streaming-ca6c680d8d6b4da8b64cbbebf892d1d7`, with
  `Authority load skipped native neighbour population or regenerated imported terrain`.
- Offline `forge1710` Gradle `build` passes, including native client-provider tests
  for absent chunks, allocation before packet receipt, all-air receipt, negative
  coordinates and unload. Four Python streaming harness tests pass.
- `python scripts/test_streaming_runtime.py --version 1.7.10 --explore` passes in
  `streaming-b97014785c6c4751a351f064579d3988`: neighbour population preserves the
  imported marker; 512-block movement unloads the origin and restores its edit on
  return; the client receives all nine nearby chunks before and after exploration.
  No sampled server tick lacks the player's resident chunk. The flat test's tick
  interval P99 is 71.66 ms; this is not a performance guarantee for arbitrary saves.
- `python scripts/test_streaming_adopted.py --target-version 1.7.10 --native-terrain`
  passes in `streaming-adopted-dc8054c93089448e9571c350b0329b46`: a real adopted
  1.20.1 -> 1.7.10 -> 1.20.1 handoff preserves the original window, releases target
  input, advances at least 40 ticks, receives all nine nearby chunks, and restores
  emerald/diamond edits and cherry planks on return. The final target frame visibly
  contains the native mountain terrain; unsupported states remain placeholders.
  Host and clients shut down normally. Handoffs measured 14.83 s outbound and 2.51 s
  returning, excluding cold target startup. The native test uses a harness-only
  flying viewpoint 12 blocks above the surface: the flat fixture's Y=66 was inside
  a mountain and its collision movement invalidated the earlier test attempt.
  The 1.20.1 fixture build and its native environment tests also pass.

The user's original 1.7.10 log and status were copied to
`build/diagnostics/legacy-chunks/reported-latest.log` and `reported-status.json`
before launching tests. Runtime tests use new isolated saves.
# Parallel prewarming (2026-10-06)

All five version-list screens now provide an independent prewarm action. The host runs target preparation as cooperative jobs: helper processes and Minecraft startup can overlap, while window attachment and IPC stay on the coordinator thread. A target becomes ready only after client render readiness and the matching freeze acknowledgement. Prewarming does not export the active world, select a version, or resume a target.

Validated with `HostWarmupTest` (parallel startup, deduplication, readiness acknowledgement, independent failures, retry, timeout, cancellation and exited clients), `AutoRuntimeTest` (separate session-bound requests, no switch request, immediate pending state, retries and stale-host rejection), and Python discovery tests for managed clients before window attachment. The complete shared tests, 57 Python tests, host focus tests, and all five offline adapter builds passed. Current build artifacts were inspected for the prewarm UI and request protocol before installation. No real-client UI or visual verification was performed for this feature.
