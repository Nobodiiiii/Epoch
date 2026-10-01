# Epoch Forge 1.20.1 adapter

Build with Java 17: `gradlew.bat build`. The reobfuscated artifact is `build/libs/epoch-0.1.0.jar`.

The compile target is Forge 47.4.0; the Epoch local runtime uses Forge 47.4.23. This uses the shared source protocol under `../src/shared/java`.

Only launch through the Epoch host for switching. Runtime state belongs under `../.minecraft`, not this module's Gradle `run` directory.

Features: phase key right-click request; bounded block-state snapshot; opaque unknown-block NBT and restoration; precise rendered camera and game-frame capture; frozen integrated-server command loop; complete-region chunk packets and lighting barrier; renderer-readiness heartbeat; delayed input handoff.

The v0.1 shared region is x/z [-32,32), y [0,256), Overworld, one player. This is a bounded block-world bridge, not an arbitrary save converter. Entities, scheduled ticks, unsupported inventory items, and version-specific vanilla block-entity schema changes do not have a general lossless migration contract.

`/epoch testsetup` creates the lab, key, chest sentinel and a chiseled bookshelf with a written-book NBT payload. `/epoch probe` records block/NBT/camera evidence in the bridge status diagnostics.

Unknown-block break drops and creative cloning preserve `OriginalState`, `OriginalNbt`, and `SourceVersion`. A destroyed unknown block stays destroyed; a preserved/replaced unknown item can restore its original block at its new location when exported to a compatible version.
