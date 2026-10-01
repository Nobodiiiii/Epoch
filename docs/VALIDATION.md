# Epoch 0.2 — validation status, 2026-10-01

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
