# Epoch GLFW build

The active build is an **altered LWJGL GLFW distribution**, built for Windows x64, from https://github.com/LWJGL-CI/GLFW at commit `03f63972b7238a179123f4cb3fa2c2539d5a6dea` (reports version 3.5.1). Its license is preserved in `glfw-lwjgl/LICENSE.md`. The earlier upstream `glfw-3.3.9` source is retained only as an investigation artifact and is not built or loaded: it lacks LWJGL-required extension exports.

Changes in `src/win32_window.c` are marked `Epoch`:

1. `_glfwPollEventsWin32` resolves the focused HWND through this process's own `_glfw.windowListHead`, instead of dereferencing a public `GLFW` property of an arbitrary active window. Windows cross-process `SetParent` joins input queues; the active/focused HWND can belong to another Minecraft process and its property contains a pointer invalid in the current address space. This caused native access violations in both original clients during embedding.
2. `_glfwWindowFocusedWin32` compares `GetFocus()` rather than `GetActiveWindow()`. The former identifies the embedded GLFW child; the latter identifies the Java top-level host.

`build.ps1` uses the installed CMake and MinGW-w64 toolchain to produce `epoch-glfw.dll`. If the local patched checkout is absent, it downloads the pinned commit archive, checks all extraction paths, and applies `epoch-glfw.patch` inside a new isolated `dependencies` directory. Existing source checkouts are preserved. `-UsePinnedArchive -PrepareOnly` verifies this bootstrap path without compiling or replacing the running DLL. The standalone license is `GLFW-LICENSE.md`.

The launcher explicitly passes the DLL's absolute path in `org.lwjgl.glfw.libname` to both clients. No original Minecraft library or native archive is overwritten. The DLL imports only Windows system libraries, not additional MinGW runtime DLLs.

## Adopted-window targets (v0.2)

`build.ps1 -ChildStartup` produces **`epoch-glfw-child.dll`** and `export-verification-child.json`, preserving the previous `epoch-glfw.dll` (which may still be loaded by a running legacy test). The host selects this variant for owned adopted-mode targets and sets `EPOCH_HIDDEN_START=1` in those child processes only. With that variable set, patched GLFW suppresses showing, restoring, attention and focus while its native window is not yet a parented `WS_CHILD`. This prevents a second top-level loading window and startup focus stealing. The host can show it normally after verified attachment and import readiness. Target options must disable exclusive fullscreen. An original user-launched instance retains its existing stock GLFW and original top-level HWND.

The complete `epoch-glfw.patch` includes both the earlier cross-process focus fix and this environment-gated startup change. It was checked against the clean pinned source. The child variant builds successfully, exports all 127/135 Windows symbols used by LWJGL 3.2.2/3.3.1, and passes `BindingSmoke` under Java 8 and Java 17 without opening a window. Actual hidden-window rendering readiness and mouse focus still require live game tests; export/binding checks do not prove those properties.

This is a prototype compatibility patch, not an upstream GLFW release. A successful native compilation must be followed by real-client focus, input and repeated-switch testing.

`verify_exports.py` reads the installed LWJGL JARs from the project's `.minecraft/libraries` directory, inspects their function tables, and checks all Windows-relevant symbols against the DLL. It verified 127 symbols for LWJGL 3.2.2 and 135 for 3.3.1; the full record is `export-verification.json`. `BindingSmoke.java` also initialized the actual GLFW, NativeWin32 and NativeWGL binding classes successfully under Java 8 / LWJGL 3.2.2 and Java 17 / LWJGL 3.3.1, without opening a window.
