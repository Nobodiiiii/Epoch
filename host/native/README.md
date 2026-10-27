# Epoch GLFW build

The active build is an **altered LWJGL GLFW distribution**, built for Windows x64, from https://github.com/LWJGL-CI/GLFW at commit `03f63972b7238a179123f4cb3fa2c2539d5a6dea` (reports version 3.5.1). Its license is preserved in `glfw-lwjgl/LICENSE.md`. The earlier upstream `glfw-3.3.9` source is retained only as an investigation artifact and is not built or loaded: it lacks LWJGL-required extension exports.

Changes in `src/win32_window.c` are marked `Epoch`:

1. `_glfwPollEventsWin32` resolves the focused HWND through this process's own `_glfw.windowListHead`, instead of dereferencing a public `GLFW` property of an arbitrary active window. Windows cross-process `SetParent` joins input queues; the active/focused HWND can belong to another Minecraft process and its property contains a pointer invalid in the current address space. This caused native access violations in both original clients during embedding.
2. `_glfwWindowFocusedWin32` compares `GetFocus()` rather than `GetActiveWindow()`. The former identifies the embedded GLFW child; the latter identifies the Java top-level host.

`build.ps1` uses the installed CMake and MinGW-w64 toolchain to produce `epoch-glfw.dll`. If the local patched checkout is absent, it downloads the pinned commit archive, checks all extraction paths, and applies `epoch-glfw.patch` inside a new isolated `dependencies` directory. Existing source checkouts are preserved. `-UsePinnedArchive -PrepareOnly` verifies this bootstrap path without compiling or replacing the running DLL. The standalone license is `GLFW-LICENSE.md`.

The launcher explicitly passes the DLL's absolute path in `org.lwjgl.glfw.libname` to both clients. No original Minecraft library or native archive is overwritten. The DLL imports only Windows system libraries, not additional MinGW runtime DLLs.

## Adopted-window targets (v0.2)

This is a prototype compatibility patch, not an upstream GLFW release. A successful native compilation must be followed by real-client focus, input and repeated-switch testing.

`verify_exports.py` reads the installed LWJGL function tables and compares required Windows symbols with the compiled DLL. The build uses the configured Java 17 runtime and native tools from `PATH`, `EPOCH_GCC`, and `EPOCH_MAKE`.
