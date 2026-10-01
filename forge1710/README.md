# Epoch adapter for Minecraft 1.7.10

Build with Gradle 8.8 on Java 17 and a Java 8 toolchain. RetroFuturaGradle 1.4.9 targets Forge 10.13.4.1614. `gradle build` produces the deployable `build/libs/epoch-1.7.10-0.3.0.jar`; the `-dev.jar` is only for development.

The adapter registers the Epoch Key (Tools creative tab, clock + ender pearl shapeless recipe), the instance selection screen (`/epoch versions`), the unknown block with persistent original state/NBT, automatic runtime attachment and the shared snapshot transport. Unknown block drops and creative pick-block copies carry `BlockEntityTag`; placing them restores their payload at the new position. The HWND comes from the running LWJGL 2 WindowsDisplay; there is no synthetic handle or additional native dependency in the mod.

`testLegacy` initializes vanilla registries in a LaunchClassLoader without opening Minecraft or loading a world. It verifies canonical SNBT/binary NBT, block metadata restrictions, directional states, unknown state handling, item identifiers and tile payload preservation.

The shared region and single-player Overworld restrictions still apply. New-format tile payloads are decoded and roundtripped before being accepted; any field that the old tile would discard causes the entire block to become an unknown placeholder. Long arrays are not representable by 1.7.10 NBT and follow the same placeholder path. Player inventory accepts only main slots 0–35 and armor slots 100–103. Empty the offhand before switching to 1.7.10. Unsupported slots, duplicate slots, unavailable items, and payloads that cannot survive an ItemStack roundtrip stop the switch during preflight before world mutation.
