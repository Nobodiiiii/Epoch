# JourneyMap chunk origins

Epoch's optional JourneyMap integration colors Overworld chunks by their first
recorded generation version. Open JourneyMap's fullscreen map with **J**. The
lower-left legend pairs colors with version labels; **V** toggles the overlay.
Hover a region for its version and starting chunk coordinates. The minimap is
unchanged. JourneyMap map imagery and waypoints remain local to each profile.

| Generation version | Color |
|---|---|
| 1.7.10 | Amber |
| 1.12.2 | Sky blue |
| 1.16.5 | Green |
| 1.18.2 | Purple |
| 1.20.1 | Orange-red |
| Previously unrecorded | Gray |

`ChunkStore` records `originVersion` on a chunk's first authority commit and
preserves it on edits and handoffs. Existing references without that field remain
unknown: the last serialization version cannot prove the generating version.
The new field is published inside the existing commit journal and atomic chunk
reference update. No terrain object, player inventory, or source save is rewritten
to populate the map. All five Epoch adapters must be updated together so an old
writer cannot drop the additional reference metadata.

The map reads only reference metadata on its own worker, without taking the
authority lock, decompressing terrain, or loading/generating Minecraft chunks.
Only fullscreen Overworld bounds are queried. Adjacent same-version columns are
merged into horizontal rectangles, and overlay publication is budgeted per client
tick. Views are bounded to 512 by 512 chunks, 32,768 reference records and 4,096
rectangles. The legend asks the player to zoom in when a limit is reached. Changes
refresh approximately every five seconds while the map is open.

## Installation and build

Close all game instances, then run:

```powershell
python scripts/install_journeymap.py
./scripts/build.ps1 -Offline
```

The installer uses the official Modrinth download URLs and SHA-512 hashes pinned
in `scripts/journeymap-files.json`. It installs JourneyMap 6.0.8 for 1.7.10, 6.0.10
for 1.12.2, 6.0.9 for 1.16.5, 5.9.8 for 1.18.2 and 6.0.7 for 1.20.1, plus UniMixins
on 1.7.10 and Common Networking on 1.16.5. Required 1.20.1 dependencies are embedded
by JourneyMap. Previous matching map mods are backed up under
`epoch/map-mod-backups` in each instance; unrelated mods are preserved.

`python scripts/install_journeymap.py --download-only` prepares compile-only APIs
without touching running profiles. `scripts/build.ps1` runs this preparation too;
its Gradle `-Offline` switch does not prohibit these downloads if the pinned cache
has not yet been populated. JourneyMap API classes are not bundled into Epoch.
