#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <string.h>
#include <jni.h>

/* Only this process's already loaded LWJGL import table is changed. No disk files or
 * other processes are modified, and the ordinary source client never loads this DLL. */
static LONG denied_calls;
static LONG guarded_creations;
static LONG installed_mask;
static HWND last_display;

/* Win32 reports the top-level source as foreground after cross-process SetParent.
 * LWJGL2 compares that handle with its own display before clipping/recentering.
 * Resolve only our visible, focused child of the actual foreground root so an
 * inactive target cannot pass LWJGL's display-handle comparison. */
static int focused_foreground_child(HWND window, HWND foreground) {
    DWORD process = 0;
    if (!window || !foreground || !IsWindow(window) || !IsWindowVisible(window)) return 0;
    GetWindowThreadProcessId(window, &process);
    return process == GetCurrentProcessId()
        && (GetWindowLongPtrW(window, GWL_STYLE) & WS_CHILD)
        && GetParent(window) && GetAncestor(window, GA_ROOT) == foreground;
}

static HWND WINAPI guarded_GetForegroundWindow(void) {
    HWND foreground = GetForegroundWindow();
    HWND focused = GetFocus();
    return focused_foreground_child(focused, foreground) ? focused : foreground;
}

/* DefWindowProc forwards WM_SETCURSOR to the source parent first, whose cursor
 * can replace LWJGL2's blank gameplay cursor. Keep the focused child's own class
 * cursor in its client area, including the normal arrow when a menu ungrabs it. */
static int set_child_cursor(HWND window, UINT message, LPARAM parameter) {
    if (message != WM_SETCURSOR || LOWORD(parameter) != HTCLIENT
            || GetFocus() != window
            || !focused_foreground_child(window, GetForegroundWindow())) return 0;
    HCURSOR cursor = (HCURSOR)GetClassLongPtrW(window, GCLP_HCURSOR);
    SetCursor(cursor ? cursor : LoadCursorW(NULL, (LPCWSTR)IDC_ARROW));
    return 1;
}

static LRESULT WINAPI guarded_DefWindowProcW(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (set_child_cursor(window, message, lparam)) return TRUE;
    return DefWindowProcW(window, message, wparam, lparam);
}

static LRESULT WINAPI guarded_DefWindowProcA(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (set_child_cursor(window, message, lparam)) return TRUE;
    return DefWindowProcA(window, message, wparam, lparam);
}

static int waiting_for_parent(HWND window) {
    DWORD process = 0;
    if (!window || !IsWindow(window)) return 0;
    GetWindowThreadProcessId(window, &process);
    if (process != GetCurrentProcessId()) return 0;
    return !(GetWindowLongPtrW(window, GWL_STYLE) & WS_CHILD) || !GetParent(window);
}

static BOOL WINAPI guarded_ShowWindow(HWND window, int command) {
    if (command != SW_HIDE && waiting_for_parent(window)) {
        InterlockedIncrement(&denied_calls);
        return FALSE;
    }
    return ShowWindow(window, command);
}

static BOOL WINAPI guarded_ShowWindowAsync(HWND window, int command) {
    if (command != SW_HIDE && waiting_for_parent(window)) {
        InterlockedIncrement(&denied_calls);
        return TRUE;
    }
    return ShowWindowAsync(window, command);
}

static BOOL WINAPI guarded_SetWindowPos(HWND window, HWND after, int x, int y,
                                       int width, int height, UINT flags) {
    if (waiting_for_parent(window)) {
        if ((flags & SWP_SHOWWINDOW) || !(flags & SWP_NOACTIVATE))
            InterlockedIncrement(&denied_calls);
        flags = (flags & ~SWP_SHOWWINDOW) | SWP_NOACTIVATE;
    }
    return SetWindowPos(window, after, x, y, width, height, flags);
}

static BOOL WINAPI guarded_SetForegroundWindow(HWND window) {
    if (waiting_for_parent(window)) {
        InterlockedIncrement(&denied_calls);
        return FALSE;
    }
    return SetForegroundWindow(window);
}

static HWND WINAPI guarded_SetFocus(HWND window) {
    if (waiting_for_parent(window)) {
        InterlockedIncrement(&denied_calls);
        return NULL;
    }
    return SetFocus(window);
}

static HWND WINAPI guarded_SetActiveWindow(HWND window) {
    if (waiting_for_parent(window)) {
        InterlockedIncrement(&denied_calls);
        return NULL;
    }
    return SetActiveWindow(window);
}

static BOOL WINAPI guarded_BringWindowToTop(HWND window) {
    if (waiting_for_parent(window)) {
        InterlockedIncrement(&denied_calls);
        return TRUE;
    }
    return BringWindowToTop(window);
}

static BOOL WINAPI guarded_FlashWindow(HWND window, BOOL invert) {
    if (waiting_for_parent(window)) {
        InterlockedIncrement(&denied_calls);
        return FALSE;
    }
    return FlashWindow(window, invert);
}

static BOOL WINAPI guarded_FlashWindowEx(PFLASHWINFO info) {
    if (info && waiting_for_parent(info->hwnd)) {
        InterlockedIncrement(&denied_calls);
        return FALSE;
    }
    return FlashWindowEx(info);
}

static HWND WINAPI guarded_CreateWindowExW(DWORD extended, LPCWSTR class_name,
        LPCWSTR title, DWORD style, int x, int y, int width, int height,
        HWND parent, HMENU menu, HINSTANCE instance, LPVOID parameter) {
    int deferred = !(style & WS_CHILD) || !parent;
    if (deferred) {
        if (style & WS_VISIBLE) InterlockedIncrement(&denied_calls);
        style &= ~WS_VISIBLE;
        InterlockedIncrement(&guarded_creations);
    }
    HWND result = CreateWindowExW(extended, class_name, title, style, x, y,
                                 width, height, parent, menu, instance, parameter);
    if (deferred && result) last_display = result;
    return result;
}

static HWND WINAPI guarded_CreateWindowExA(DWORD extended, LPCSTR class_name,
        LPCSTR title, DWORD style, int x, int y, int width, int height,
        HWND parent, HMENU menu, HINSTANCE instance, LPVOID parameter) {
    int deferred = !(style & WS_CHILD) || !parent;
    if (deferred) {
        if (style & WS_VISIBLE) InterlockedIncrement(&denied_calls);
        style &= ~WS_VISIBLE;
        InterlockedIncrement(&guarded_creations);
    }
    HWND result = CreateWindowExA(extended, class_name, title, style, x, y,
                                 width, height, parent, menu, instance, parameter);
    if (deferred && result) last_display = result;
    return result;
}

typedef struct { const char *name; void *replacement; unsigned mask; } Hook;
static Hook hooks[] = {
    {"ShowWindow", guarded_ShowWindow, 1},
    {"SetWindowPos", guarded_SetWindowPos, 2},
    {"SetForegroundWindow", guarded_SetForegroundWindow, 4},
    {"SetFocus", guarded_SetFocus, 8},
    {"CreateWindowExW", guarded_CreateWindowExW, 16},
    {"CreateWindowExA", guarded_CreateWindowExA, 32},
    {"ShowWindowAsync", guarded_ShowWindowAsync, 64},
    {"SetActiveWindow", guarded_SetActiveWindow, 128},
    {"BringWindowToTop", guarded_BringWindowToTop, 256},
    {"FlashWindow", guarded_FlashWindow, 512},
    {"FlashWindowEx", guarded_FlashWindowEx, 1024},
    {"GetForegroundWindow", guarded_GetForegroundWindow, 2048},
    {"DefWindowProcW", guarded_DefWindowProcW, 4096},
    {"DefWindowProcA", guarded_DefWindowProcA, 8192}
};

static unsigned patch_lwjgl(HMODULE module) {
    BYTE *base = (BYTE *) module;
    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *)base;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return 0;
    IMAGE_NT_HEADERS *nt = (IMAGE_NT_HEADERS *)(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return 0;
    IMAGE_DATA_DIRECTORY entry = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (!entry.VirtualAddress || !entry.Size) return 0;
    IMAGE_IMPORT_DESCRIPTOR *descriptor = (IMAGE_IMPORT_DESCRIPTOR *)(base + entry.VirtualAddress);
    unsigned mask = 0;
    for (; descriptor->Name; descriptor++) {
        if (_stricmp((char *)(base + descriptor->Name), "USER32.dll")) continue;
        if (!descriptor->OriginalFirstThunk) return 0;
        IMAGE_THUNK_DATA *name = (IMAGE_THUNK_DATA *)(base + descriptor->OriginalFirstThunk);
        IMAGE_THUNK_DATA *target = (IMAGE_THUNK_DATA *)(base + descriptor->FirstThunk);
        for (; name->u1.AddressOfData; name++, target++) {
            if (IMAGE_SNAP_BY_ORDINAL(name->u1.Ordinal)) continue;
            IMAGE_IMPORT_BY_NAME *import = (IMAGE_IMPORT_BY_NAME *)(base + name->u1.AddressOfData);
            for (unsigned i = 0; i < sizeof(hooks) / sizeof(hooks[0]); i++) {
                if (strcmp((const char *)import->Name, hooks[i].name)) continue;
                DWORD old_protection, unused;
                if (!VirtualProtect(&target->u1.Function, sizeof(void *), PAGE_READWRITE, &old_protection))
                    return 0;
                InterlockedExchangePointer((void *volatile *)&target->u1.Function, hooks[i].replacement);
                if (!VirtualProtect(&target->u1.Function, sizeof(void *), old_protection, &unused))
                    return 0;
                mask |= hooks[i].mask;
            }
        }
    }
    return mask;
}

JNIEXPORT jint JNICALL Java_dev_epoch_legacy_EpochLegacyAgent_install(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (installed_mask) return installed_mask;
    WCHAR enabled[8];
    if (GetEnvironmentVariableW(L"EPOCH_HIDDEN_START", enabled, 8) != 1 || enabled[0] != L'1') return 0;
    HMODULE lwjgl = GetModuleHandleW(L"lwjgl64.dll");
    if (!lwjgl) lwjgl = GetModuleHandleW(L"lwjgl.dll");
    if (!lwjgl) return 0;
    installed_mask = (LONG)patch_lwjgl(lwjgl);
    return installed_mask;
}

JNIEXPORT jlongArray JNICALL Java_dev_epoch_legacy_EpochLegacyAgent_diagnostics(JNIEnv *env, jclass cls) {
    (void)cls;
    HWND display = last_display;
    jlong values[] = { installed_mask, denied_calls, guarded_creations,
        (jlong)(intptr_t)display, IsWindowVisible(display),
        (jlong)(GetWindowLongPtrW(display, GWL_STYLE) & WS_VISIBLE),
        waiting_for_parent(display) };
    jlongArray result = (*env)->NewLongArray(env, 7);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 7, values);
    return result;
}

/* A never-shown same-process parent allows smoke tests to exercise post-adoption
 * visibility without displaying any test windows on the user's desktop. */
JNIEXPORT jlong JNICALL Java_dev_epoch_legacy_EpochLegacyAgent_adoptForSmoke(JNIEnv *env, jclass cls, jlong child_value) {
    (void)env; (void)cls;
    HWND child = (HWND)(intptr_t)child_value;
    HWND parent = CreateWindowExW(0, L"STATIC", L"Epoch hidden binding smoke", WS_OVERLAPPED,
                                  0, 0, 96, 96, NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (!parent || !IsWindow(child)) return 0;
    LONG_PTR style = GetWindowLongPtrW(child, GWL_STYLE);
    SetWindowLongPtrW(child, GWL_STYLE, (style & ~(WS_POPUP | WS_CAPTION | WS_THICKFRAME)) | WS_CHILD);
    SetLastError(0);
    HWND previous = SetParent(child, parent);
    if (!previous && GetLastError()) { DestroyWindow(parent); return 0; }
    // Use the guard itself here; the smoke confirms it permits WS_VISIBLE after adoption.
    guarded_ShowWindow(child, SW_SHOWNA);
    return (jlong)(intptr_t)parent;
}

JNIEXPORT void JNICALL Java_dev_epoch_legacy_EpochLegacyAgent_releaseSmokeParent(JNIEnv *env, jclass cls, jlong value) {
    (void)env; (void)cls;
    if (value) DestroyWindow((HWND)(intptr_t)value);
}
