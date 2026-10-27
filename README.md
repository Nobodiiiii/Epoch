# Epoch 0.3 — cross-version single-player prototype

Switch between real **Forge 1.7.10, 1.12.2, 1.16.5, 1.18.2 and 1.20.1** clients while keeping the original Windows game window. Enter a single-player world, right-click the **Epoch Key / 纪元之钥**, and choose a local instance from the list.

Epoch uses separate Minecraft processes and version adapters. The running instance automatically starts the coordinator; only the initial instance needs to be started manually. This checkout still requires its Python launcher, Java host, Java 8/17 runtimes, native Windows components, and local Minecraft/Forge dependencies. It is **not a self-contained single-JAR or cross-platform release**.

The October 13 architecture audit implementation is described in [the optimization record](docs/AUDIT_OPTIMIZATION_IMPLEMENTATION_2026-10-13.md), including checkpoint compatibility, runtime configuration and resource budgets. All adapters must be updated together to streaming protocol 5.

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

Each instance also has a **预热 (Prewarm)** button. It prepares that instance in the background without switching versions or closing the list; several instances can warm up concurrently. Buttons show preparing, ready, or retry after failure. A ready instance stays hidden and frozen until selected, and switching reuses its existing process. Prewarmed clients use a bounded target heap budget and are released after an idle interval; ready non-active clients also have a release action.

Discovery reads `.minecraft/versions` and launcher profiles under this checkout. It also recognizes legacy local `versions` and existing `instances` directories if supplied, but does not create replacement development instances. Launcher records pointing to missing directories are ignored. Minecraft version inheritance is supported. It does not download Forge or turn unsupported versions into supported ones. Prism/MultiMC metadata is currently listed as unsupported.

## Shared-world scope

### Legacy block and item compatibility

1.7.10 and 1.12.2 store many variants as numeric block IDs and metadata. Epoch converts the supported vanilla combinations through a shared flattening table. States without a safe mapping keep their original version and metadata in the unknown block; another version does not reinterpret that metadata as one of its own blocks.

Item compatibility is deliberately conservative. Common renamed block items and supported durability fields are converted, but this is not a complete item data fixer. Carried items that cannot survive the destination registry and NBT round trip become non-usable `epoch:unavailable_item` envelopes in the player inventory or ender chest. The envelope retains the original item and NBT, while its current count and slot follow splits and moves. A later handoff or shared-checkpoint restore to a compatible version restores the item. Envelopes can be saved normally; discarding one discards that stack. All five adapters must be updated together. Invalid item data, envelopes exceeding the native NBT string size limit, and unsupported inventory slots (including an occupied offhand when entering 1.7.10) still stop the handoff before world mutation. Container block compatibility remains conservative as described below.

Containers are loaded into fresh block entities and their saved NBT is checked against the expected payload. If the target would discard an item or field, the entire block falls back to an unknown block with the original payload. That block can be restored in a version that represents it. This preserves data, but does not make the unavailable container usable in the older version.

## Save, quit, and reopen in another version

Saving the active player publishes a restart checkpoint containing the player, inventory, world settings and the full immutable terrain index after pending terrain writes finish. Save and quit all instances before independently opening the same world in another supported profile. The persistent last-writer ID no longer prevents a restart after its processes exit; process locks still reject a second independent writer while any linked instance is running.

World binding and explicit refresh prepare an entry in every available supported local profile in the background. Existing linked save names are retained; newly linked entries use `<original save name> [Epoch <world ID prefix>]`. Select that entry from Singleplayer in any profile. It restores the latest shared checkpoint through that version's compatibility adapter. Switching back to the original version reuses its original save. Existing inventory/content compatibility limits still apply.

Checkpoints are selected atomically; the selected checkpoint and two previous complete checkpoints remain under `.minecraft/epoch/worlds/<world ID>/saved/`. Worlds created before this mechanism use their local player state on their first reopen, then join the shared save flow. Unfinished native generation records are not treated as saved source terrain. Previously existing **full** source chunks that have never been read into Epoch's authority still require the source-version reader; this change does not implement offline conversion of arbitrary pre-Epoch saves.

## Local dependencies and files

Set `EPOCH_JAVA8_HOME` and `EPOCH_JAVA17_HOME`, or copy `epoch-runtime.example.json` to `epoch-runtime.json`. Preparation checks the selected JDK major versions and writes resolved paths into each launch manifest. Native build tools use `PATH`, `EPOCH_GCC` and `EPOCH_MAKE`.

`scripts/build.ps1` assembles/reobfuscates all five adapters, compiles the coordinator and legacy native components, then installs adapters into closed supported profiles. Build and maintenance entry points do not run tests. Shared restart checkpoints select the player, environment and the complete immutable terrain index together, retaining the selected checkpoint and two previous complete checkpoints.
