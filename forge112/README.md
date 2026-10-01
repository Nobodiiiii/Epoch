# Epoch Forge 1.12.2 adapter

Build with Java 17 running Gradle 8.8, plus a Java 8 compilation toolchain:

```powershell
.\gradlew.bat build protocolSmoke
```

The release artifact is `build/libs/epoch-1.12.2-0.3.0.jar`. ForgeGradle 6 reads Forge 14.23.5.2860 with stable 39 mappings; the installed Forge 14.23.5.2864 runtime is compatible. `protocolSmoke` initializes vanilla registries without creating a client, world, or display, and checks block names, item metadata, tool damage, container payloads, and rejection of unavailable inventory items.

The adapter uses the same bounded snapshot and local instance protocol as the modern modules. LWJGL 2 supplies the Win32 window handle through reflection. Unknown blocks preserve original block state and block entity SNBT in their tile entity, drops, and pick-block result.

`LegacyStates` uses the shared explicit block flattening table. An unsupported name or state property becomes an unknown block. `LegacyItems` converts supported block item metadata, common item renames, and tool damage; unsupported inventory data cancels import before world mutation. Block entity payloads that cannot survive a load/save cycle become unknown blocks with the untouched source payload. This is intentionally conservative for old entity schemas and state properties such as leaf decay.

Real rendering, adopted window behavior, and cross-version world switching require the integration tests coordinated from the project root under `.minecraft`.
