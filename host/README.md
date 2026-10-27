# Epoch host (Windows, Java 17)

## Adopt an existing Minecraft window

The normal v0.2 path starts from any supported Forge instance containing Epoch. Its mod publishes an adoption seed and invokes the discovery helper. The helper writes `.minecraft/epoch/catalog.json`, then starts:

```
java EpochHost <projectRoot> --adopt <seed.json>
```

The original Minecraft **native Windows HWND** remains the top-level window for the whole session. The host validates its owner PID, a live source process, the seed's freshness (five minutes), and its session/source IDs against the catalog. It creates no Swing window in this mode and never hides, moves, or reparents the original window. `WS_CLIPCHILDREN` is temporarily enabled on the original window so its OpenGL rendering respects attached target surfaces.

Only a selected target is prepared and launched. The host invokes `scripts/launch_epoch.py --prepare-target <catalogId> --seed <seed.json>` using the seed's optional `pythonExecutable` (otherwise `python` from PATH). Preparation installs the matching adapter in a stopped target and writes its launch manifest. Launching or replacing jars in other already-running instances is not a supported preparation operation.

The target must use the supplied patched GLFW DLL: the original stock GLFW window has already been created and cannot retrospectively acquire the child-window patch. A target is hidden and verified against its owned process PID before reparenting. Child bounds follow the root client rectangle without changing the original window's position or size.

Adopted-mode targets use `epoch-glfw-child.dll` with `EPOCH_HIDDEN_START=1` set by the host. This suppresses GLFW startup show/focus calls until the target has actually been parented, preventing cold loading from opening a second visible top-level window. The original running process and the previous `epoch-glfw.dll` remain untouched.

## Handoff

During `PREPARING`, the original active world stays playable while the target cold-loads. Only once the target is render-ready does `SWITCHING` begin. Export freezes the source simulation/camera and publishes its actual framebuffer. The source mod keeps that frozen framebuffer displayed; the host never hides the source early. The target imports into its dedicated replica save and acknowledges `IMPORTED` with `clientReady=true`. Then the host reveals/focuses the target and resumes it. Returning to the root follows the same import/render barrier before the foreground child is hidden.

Only one simulation may resume. Failed preparation leaves the source playable. A failed handoff freezes or terminates an owned target before resuming the source. An uncertain original root is never forcibly killed; that failure is reported for recovery. The host manages only processes it launched. If the original process closes, owned targets are asked to save and exit. On an explicit coordinator shutdown while the root remains alive, the host attempts to hand the latest state back to the root, then leaves that user-owned process running.

## Runtime contract

Files are beneath `<projectRoot>/.minecraft/epoch`:

IPC commands `{id,action,path?}` remain `export`, `import`, `freeze`, `resume`, and `shutdown`; status replies correlate `commandId`. Import readiness must include actual rendered target readiness, not just server mutation completion. Source and target native window handles must come from `GLFWNativeWin32.glfwGetWin32Window`, not GLFW's opaque window pointer.
