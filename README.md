# Epoch 0.3 — cross-version single-player prototype

Switch between real **Forge 1.7.10, 1.12.2, 1.16.5, 1.18.2 and 1.20.1** clients while keeping the original Windows game window. Enter a single-player world, right-click the **Epoch Key / 纪元之钥**, and choose a local instance from the list.

Epoch uses separate Minecraft processes and version adapters. The running instance automatically starts the coordinator; only the initial instance needs to be started manually. This checkout still requires its Python launcher, Java host, Java 8/17 runtimes, native Windows components, and local Minecraft/Forge dependencies. It is **not a self-contained single-JAR or cross-platform release**.

## Start from any supported version

To rebuild the current checkout and update Epoch in every supported local HMCL profile, close the game instances and double-click **`update_all_instances.bat`** in the project root. It builds/checks all five adapters and host components, installs the matching JARs, backs up previous Epoch JARs, and keeps the window open with the result. Logs are saved under `build/update-logs`. Unrelated mods are preserved. This updates Epoch from local source; it does not download newer source or update third-party mods.

The default uses cached build dependencies. Run `update_all_instances.bat -Online` to allow Gradle dependency downloads, or `update_all_instances.bat -InstallOnly` to install existing local build artifacts without rebuilding. Installation targets existing supported profiles under `.minecraft/versions` and refuses running instances.

The local launcher profiles and their corresponding adapters are:

| Minecraft | Local Forge version | Client Java | Adapter module | Installed game directory |
|---|---|---|---|---|
| 1.7.10 | 10.13.4.1614 | 8 | `forge1710/` | `.minecraft/versions/1.7.10-Forge/` |
| 1.12.2 | 14.23.5.2864 | 8 | `forge112/` | `.minecraft/versions/1.12.2-Forge/` |
| 1.16.5 | 36.2.42 | 8 | `forge116/` | `.minecraft/versions/1.16.5-Forge/` |
| 1.18.2 | 40.3.12 | 17 | `forge118/` | `.minecraft/versions/1.18.2-Forge/` |
| 1.20.1 | 47.4.23 | 17 | `forge120/` | `.minecraft/versions/1.20.1-Forge/` |

Each module produces its own adapter JAR in `build/libs`. Other profile names are detected from their metadata; the game version must match the adapter. Runtime test coverage and measured results are recorded separately in [VALIDATION.md](docs/VALIDATION.md).

Optional JourneyMap support adds a fullscreen chunk-origin overlay, with a version
legend and **V** toggle. Each Forge version needs its own map build; see
[JourneyMap installation and origin tracking](docs/JOURNEYMAP_ORIGINS.md).

1. Build/install the adapters while the target instances are closed:

   ```powershell
   ./scripts/build.ps1 -Offline
   ```

   If already built, `./scripts/install_mods.ps1` safely installs the latest adapters into supported discovered launcher profiles, including HMCL's isolated `.minecraft/versions/<profile>/mods` directories. Existing Epoch JARs are backed up, unrelated mods are preserved, and installation refuses running targets.

2. Set HMCL's game directory to **`D:\Files\Epoch\.minecraft`**, then start **one** local Forge instance with its matching Epoch adapter. Its mods and saves stay inside the existing profile directory listed above. Enter a single-player Overworld save; the mod then detects nearby instances and starts the coordinator automatically.
3. Obtain `epoch:phase_key` using `/give @p epoch:phase_key` when commands are enabled, or craft a clock with an ender pearl. It is also in the creative Tools tab on 1.7.10, 1.12.2 and 1.18.2, Miscellaneous on 1.16.5, and Tools & Utilities on 1.20.1. It is not automatically given on world entry. Right-click the key to open the instance list. `/epoch versions` also opens the list.
4. Choose a supported instance. Current, incompatible and independently running instances are unavailable, with reasons shown. Refresh rescans local profiles. Epoch installs the correct adapter into a closed target before starting it.

Each instance also has a **预热 (Prewarm)** button. It prepares that instance in the background without switching versions or closing the list; several instances can warm up concurrently. Buttons show preparing, ready, or retry after failure. A ready instance stays hidden and frozen until selected, and switching reuses its existing process. Prewarmed clients continue to consume memory until the coordinator closes.

Discovery reads `.minecraft/versions` and launcher profiles under this checkout. It also recognizes legacy local `versions` and existing `instances` directories if supplied, but does not create replacement development instances. Launcher records pointing to missing directories are ignored. Minecraft version inheritance is supported. It does not download Forge or turn unsupported versions into supported ones. Prism/MultiMC metadata is currently listed as unsupported.

**The first switch prepares another Minecraft process and may take tens of seconds or longer. You can keep playing in the source while preparation runs.** Only the final world handoff freezes the view. Earlier 1.20.1 ↔ 1.16.5 lab handoffs measured around one second; that result is not a timing guarantee for every version pair. Cold startup is separate and is not a ten-second guarantee.

The original source HWND remains the visible top-level game window. The source starts with its normal graphics library. Modern targets use the patched GLFW component; 1.7.10 and 1.12.2 targets use a Java agent and a Win32 guard around their existing LWJGL 2 library. The guard keeps a target hidden until the coordinator attaches it as a child of the original window. Original game libraries on disk are not replaced. Windowed mode is enforced during Epoch operation. Physical desktop focus and mouse behavior remain subject to the verification described in `docs/VALIDATION.md`.

## Shared-world scope

- **Windows x64; exactly one player; Overworld; shared height Y `[0,256)`** (Y 0 through 255). Ordinary single-player launches now enable streaming terrain by default: chunks load as the player moves, and handoffs use the current position. `-Depoch.streaming=false` explicitly selects the legacy fixed X/Z `[-32,32)` snapshot mode. Streaming remains a prototype; long-duration high-speed exploration and failure recovery are not fully validated.
- Compatible block states and block-entity NBT transfer both ways. Unsupported content becomes `epoch:unknown_block`, retaining original state and NBT for restoration on return. Removing a placeholder removes it from the shared world rather than resurrecting it later. A block may also become unknown when its identifier exists in both versions but its properties or stored data cannot be represented safely. Unknown blocks and their block items now look up the source instance model/textures and display animated glitch scanlines; unsupported visual resources retain the fallback. See [source appearance behavior and limits](docs/UNKNOWN_APPEARANCE.md).
- The 1.16.5, 1.18.2 and 1.20.1 adapters share a version-scoped, JSON-extensible block-state compatibility layer. It first accepts directly representable states, then applies explicit safe migrations and validates their output against the destination registry. Dry oak, spruce, birch, jungle, acacia and dark oak leaves can drop `waterlogged=false` when entering 1.16.5 or 1.18.2; distance and persistence are preserved. Wet leaves and unrecognized properties/values still become unknown blocks. See [state compatibility rules and diagnostics](docs/BLOCK_STATE_COMPATIBILITY.md), the [architecture plan](docs/COMPATIBILITY_ARCHITECTURE.md), and the [mod integration guide](docs/MOD_COMPATIBILITY.md). All five adapters expose read-only registry export and state audit commands. Run `scripts/audit_compatibility.ps1 -Offline` to generate a complete native vanilla state matrix; this does not test world behavior or block-entity payloads.
- Position, yaw/pitch, time and inventory entries transfer; items unavailable in the destination are carried as lossless envelopes. Each version renders natively; small lighting, fog and texture differences remain possible.
- Schema 2 also transfers shared-region biomes, weather, supported game rules, health/hunger/experience, abilities, ender chest, effects, attributes and Overworld respawn state across all five adapters. Unrepresentable biome detail and inactive rules are carried with the save for later restoration; unavailable carried items are preserved in envelopes, while other incompatible player payloads stop the handoff before block changes. See [environment migration architecture, limits and validation](docs/ENVIRONMENT_MIGRATION.md). All adapters must be updated together; schema 1 remains readable.
- The destination uses a separate **`Epoch Transfer <id>`** save. Epoch does not load the source save in the other version or overwrite an unrelated existing target save. Returning to the source applies the shared-region changes to the original source world; this is a working world, not a read-only backup.
- In streaming mode, unexplored chunks use the **current instance's native terrain generator**. New transfer saves use that version's normal Overworld generation with the original world's seed and structures enabled. Existing shared/source chunks retain their terrain and edits. Old Epoch flat transfer saves are upgraded before launch, with the previous `level.dat` backed up under `epoch/level.dat.before-native-generation`; existing chunks are preserved. Customized target generators and the original source save's generator remain unchanged. Different versions can produce seams at new terrain boundaries, even with the same seed.
- Target preparation leaves the source running. During handoff, source simulation freezes, the snapshot imports into the frozen target, and only the selected instance resumes.
- Whole-world conversion in advance, content outside the shared height, entities, pending ticks, portals, arbitrary block-entity schema changes and third-party content mods are outside this prototype's supported transfer scope.

### Legacy block and item compatibility

1.7.10 and 1.12.2 store many variants as numeric block IDs and metadata. Epoch converts the supported vanilla combinations through a shared flattening table. States without a safe mapping keep their original version and metadata in the unknown block; another version does not reinterpret that metadata as one of its own blocks.

Item compatibility is deliberately conservative. Common renamed block items and supported durability fields are converted, but this is not a complete item data fixer. Carried items that cannot survive the destination registry and NBT round trip become non-usable `epoch:unavailable_item` envelopes in the player inventory or ender chest. The envelope retains the original item and NBT, while its current count and slot follow splits and moves. A later handoff or shared-checkpoint restore to a compatible version restores the item. Envelopes can be saved normally; discarding one discards that stack. All five adapters must be updated together. Invalid item data, envelopes exceeding the native NBT string size limit, and unsupported inventory slots (including an occupied offhand when entering 1.7.10) still stop the handoff before world mutation. Container block compatibility remains conservative as described below.

Containers are loaded into fresh block entities and their saved NBT is checked against the expected payload. If the target would discard an item or field, the entire block falls back to an unknown block with the original payload. That block can be restored in a version that represents it. This preserves data, but does not make the unavailable container usable in the older version.

The optional `scripts/prepare_lab.py` creates flat lab saves in an explicitly selected game directory without overwriting existing saves. Lab fixtures disable random ticks/mob spawning and use known blocks and containers. Normal use starts from the existing HMCL profiles and does not require separate development instances.

## Save, quit, and reopen in another version

Existing terrain now supports validated native-chunk reuse, bounded decoding caches, and conversion phase timings across all five adapters. In 1.7, eligible solid unknown terrain uses a chunk dictionary and cached section rendering instead of one TileEntity per block. See [terrain optimization, save format, and validation limits](docs/TERRAIN_OPTIMIZATION.md).

Saving the active player publishes a shared restart checkpoint containing the player, inventory, world settings and nearby terrain after pending terrain writes finish. Save and quit all instances before independently opening the same world in another supported profile. The persistent last-writer ID no longer prevents a restart after its processes exit; process locks still reject a second independent writer while any linked instance is running.

Normal saves prepare an entry in every available supported local profile. Existing linked save names are retained; newly linked entries use `<original save name> [Epoch <world ID prefix>]`. Select that entry from Singleplayer in any profile. It restores the latest shared checkpoint through that version's compatibility adapter. Switching back to the original version reuses its original save. Existing inventory/content compatibility limits still apply.

Checkpoints are selected atomically and previous checkpoint files remain under `.minecraft/epoch/worlds/<world ID>/saved/`. Worlds created before this mechanism use their local player state on their first reopen, then join the shared save flow. Unfinished native generation records are not treated as saved source terrain. Previously existing **full** source chunks that have never been read into Epoch's authority still require the source-version reader; this change does not implement offline conversion of arbitrary pre-Epoch saves.

`tests/test_shared.ps1` checks session exclusion, release on process death, checkpoint integrity, and native full/proto chunk detection. `scripts/test_saved_restart.py` exercises save/exit/reopen through all five real clients with no live source, checking player position, seven diamonds, remote terrain edits and newer-block restoration on return.

## Local dependencies and files

Development and real-client tests use `D:\Files\Epoch\.minecraft`. Minecraft instances, saves, assets and runtime dependencies live there: `.minecraft/assets`, `.minecraft/libraries`, `.minecraft/versions` and `.minecraft/launcher_profiles.json`. The Epoch checkout root contains source code, tooling and generated build outputs. The scripts currently refer to installed Java 8/17, Gradle 8.8 and MinGW paths; adjust those paths for another machine. Gradle runs on Java 17 and uses Java 8 toolchains for the older modules. Python must be available to the running game (or configured through `epoch.python`).

| Path | Role |
|---|---|
| `forge1710/`, `forge112/`, `forge116/`, `forge118/`, `forge120/` | Version-specific Forge adapters and their `build/libs` artifacts |
| `src/shared/java/` | Snapshot validation, local IPC and automatic runtime bootstrap |
| `src/shared/resources/assets/epoch/legacy-blockstates.json` | Vanilla block-state flattening table for legacy adapters |
| `scripts/epoch_instances.py` | Profile discovery and safe adapter deployment |
| `scripts/launch_epoch.py` | Host startup and target preparation |
| `host/EpochHost.java` | Java coordinator and Windows window integration |
| `host/native/` | Patched GLFW for modern child clients |
| `host/legacy/` | Java 8 agent, Win32 LWJGL 2 guard, source and build instructions |
| `.minecraft/versions/<profile>/` | Existing HMCL isolated profiles, including mods and saves |
| `.minecraft/assets/`, `.minecraft/libraries/` | Shared Minecraft assets and game dependencies |
| `.minecraft/epoch/catalog.json` | Discovered instances and availability |
| `.minecraft/epoch/control/<instance-id>/` | Commands, status, bootstrap logs and captured frames |
| `.minecraft/epoch/evidence/` | Runtime observations and test records |
| `.minecraft/epoch/native-smoke/` | Extracted LWJGL 2 natives used only by the optional hidden-window smoke test |
| `<gameDir>/epoch/mod-backups/` | Replaced Epoch adapters retained for recovery |

`scripts/build.ps1` builds/reobfuscates all five adapters, runs shared Java and Python tests, compiles the host and legacy window components, then safely installs adapters into discovered profiles. Every stage stops on failure. `-Offline` requires the Gradle dependencies to be cached already. Close running profiles before using the combined build/install script; an individual module's Gradle `build` does not install its JAR.

`tests/test_shared.ps1` covers snapshots/IPC plus AutoRuntime session and heartbeat checks. `tests/test_instances.py` covers discovery, migrated HMCL paths, process availability, adapter preservation and installation rollback. `tests/test_versions.py` verifies Java selection, legacy launch metadata, native-only libraries, five-version save formats and existing-save preservation using temporary fixtures.

The GLFW component's pinned source, patch, license and rebuild instructions are in [host/native/README.md](host/native/README.md). The LWJGL 2 agent contract and optional `./host/legacy/build.ps1 -Smoke` check are documented in [host/legacy/README.md](host/legacy/README.md). The smoke check creates only hidden binding-test windows and does not start Minecraft. See [docs/VALIDATION.md](docs/VALIDATION.md) for measured game results and current verification limits.
