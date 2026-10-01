# Epoch LWJGL 2 window guard

Windows x64 child clients for Minecraft 1.7.10 and 1.12.2 use the original LWJGL
2 DLLs. The Java 8 agent loads `org.lwjgl.Sys` before a display is created, then
patches only that process's LWJGL import table. The Minecraft instance files and
stock LWJGL DLLs on disk are not changed.

For an **owned target process only**, the launcher supplies:

```text
EPOCH_HIDDEN_START=1
-javaagent:<absolute-project-path>/host/legacy/epoch-legacy-agent.jar
-Depoch.legacyNative=<absolute-project-path>/host/legacy/epoch-legacy-window.dll
```

Its normal `java.library.path` or `org.lwjgl.librarypath` must already select the
target version's extracted natives. The ordinary source process needs neither
the agent nor the native guard. With the environment flag absent, the agent does
nothing.

Before the HWND has both `WS_CHILD` and a parent, the guard removes `WS_VISIBLE`
at creation and suppresses show/focus/activation requests. Window creation,
resizing, OpenGL context creation and buffer swaps continue. Once the coordinator
parents the child, normal window requests are permitted. Incompatible libraries
abort before display creation instead of silently displaying an extra window.

Build and verify:

```powershell
./host/legacy/build.ps1 -Smoke
```

This compiles a Java 8 agent and MinGW x64 native guard, then exercises LWJGL
2.9.1 and 2.9.4 bindings with hidden OpenGL windows. The smoke test verifies that
startup cannot set the visible style, and that adoption permits the child to
show while its test parent remains hidden. No Minecraft client is started.
Extracted test natives are kept inside `.minecraft/epoch/native-smoke`.

Verified 2026-10-01: both bindings reported five mandatory hooks (`31`), OpenGL
4.6, a hidden window before adoption, and successful rendering after adoption.
