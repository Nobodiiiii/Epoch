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

After adoption, the guard also fixes LWJGL 2's top-level-window assumptions:

- `GetForegroundWindow` resolves to this process's focused, visible child only
  when its root is the actual foreground window. This lets stock LWJGL perform
  its normal cursor recentering and clipping; background/hidden clients cannot
  pass that check. Minecraft still controls grabbing and menu/focus release.
- Client-area `WM_SETCURSOR` uses the focused child's class cursor before
  `DefWindowProc` can forward it to the source parent. Gameplay keeps LWJGL's
  blank cursor, and menus restore its normal arrow. Other messages and window
  borders retain the default handling.

Both input hooks are mandatory at agent startup. Rebuild the agent and native
guard together. The launcher loads these files directly from `host/legacy`;
restart an existing game session to load an updated build.

Build and verify:

```powershell
./host/legacy/build.ps1 -Smoke
```

This compiles a Java 8 agent and MinGW x64 native guard, runs `LegacyMouseTest.c`,
then exercises LWJGL 2.9.1 and 2.9.4 bindings with hidden OpenGL windows.
The native regression uses real hidden HWND trees with controlled foreground,
focus, visibility and ownership observations. It verifies foreground resolution,
focus loss, hidden/foreign targets, blank/arrow cursor selection and default
message fallback without changing the desktop cursor.
The binding smoke test verifies the required input hooks, and that
startup cannot set the visible style, and that adoption permits the child to
show while its test parent remains hidden. No Minecraft client is started.
Extracted test natives are kept inside `.minecraft/epoch/native-smoke`.

Verified 2026-10-01: both bindings reported five mandatory hooks (`31`), OpenGL
4.6, a hidden window before adoption, and successful rendering after adoption.

Verified 2026-10-03: native mouse regression passed; both bindings reported all
seven mandatory hooks (`6175`) and passed hidden creation/adoption/rendering on
OpenGL 4.6. These checks do not establish physical mouse movement or Alt-Tab
behavior in a real Minecraft session; that still requires a desktop retest.
