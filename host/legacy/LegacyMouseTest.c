#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <assert.h>
#include <stdio.h>

/* Real hidden HWND trees, with only foreground/focus/visibility observations
 * controlled. Cursor/default-proc spies keep this test off the user's desktop. */
static HWND test_foreground, test_focus, test_child;
static int test_visible, test_foreign, cursor_calls, default_calls;
static HCURSOR last_cursor;
static HWND WINAPI observed_foreground(void) { return test_foreground; }
static HWND WINAPI observed_focus(void) { return test_focus; }
static BOOL WINAPI observed_visible(HWND window) { return window == test_child && test_visible; }
static DWORD WINAPI observed_owner(HWND window, LPDWORD process) {
    DWORD thread = GetWindowThreadProcessId(window, process);
    if (test_foreign && window == test_child && process) *process = GetCurrentProcessId() + 1;
    return thread;
}
static HCURSOR WINAPI record_cursor(HCURSOR cursor) {
    cursor_calls++;
    last_cursor = cursor;
    return NULL;
}
static LRESULT WINAPI record_default(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    (void)window; (void)message; (void)wparam; (void)lparam;
    default_calls++;
    return 123;
}

#define GetForegroundWindow observed_foreground
#define GetFocus observed_focus
#define IsWindowVisible observed_visible
#define GetWindowThreadProcessId observed_owner
#define SetCursor record_cursor
#define DefWindowProcW record_default
#define DefWindowProcA record_default
#include "epoch-legacy-window.c"
#undef GetForegroundWindow
#undef GetFocus
#undef IsWindowVisible
#undef GetWindowThreadProcessId
#undef SetCursor
#undef DefWindowProcW
#undef DefWindowProcA

int main(void) {
    WNDCLASSW cls = {0};
    cls.lpfnWndProc = DefWindowProcW;
    cls.hInstance = GetModuleHandleW(NULL);
    cls.lpszClassName = L"Epoch hidden mouse regression";
    assert(RegisterClassW(&cls));
    HWND root = CreateWindowW(cls.lpszClassName, L"", WS_OVERLAPPED, 0, 0, 96, 96,
                              NULL, NULL, cls.hInstance, NULL);
    HWND other = CreateWindowW(cls.lpszClassName, L"", WS_OVERLAPPED, 0, 0, 96, 96,
                               NULL, NULL, cls.hInstance, NULL);
    test_child = CreateWindowW(cls.lpszClassName, L"", WS_CHILD, 0, 0, 96, 96,
                               root, NULL, cls.hInstance, NULL);
    assert(root && other && test_child);
    assert(!IsWindowVisible(root) && !IsWindowVisible(test_child));
    test_foreground = root;
    test_focus = test_child;
    test_visible = 1;
    assert(guarded_GetForegroundWindow() == test_child);

    /* Alt-tab, sibling/source focus, hidden target, and foreign process. */
    test_foreground = other;
    assert(guarded_GetForegroundWindow() == other);
    test_foreground = root;
    test_focus = root;
    assert(guarded_GetForegroundWindow() == root);
    test_focus = NULL;
    assert(guarded_GetForegroundWindow() == root);
    test_focus = test_child;
    test_visible = 0;
    assert(guarded_GetForegroundWindow() == root);
    test_visible = 1;
    test_foreign = 1;
    assert(guarded_GetForegroundWindow() == root);
    test_foreign = 0;

    BYTE and_mask[128], xor_mask[128] = {0};
    memset(and_mask, 0xff, sizeof(and_mask));
    HCURSOR blank = CreateCursor(cls.hInstance, 0, 0, 32, 32, and_mask, xor_mask);
    assert(blank);
    SetClassLongPtrW(test_child, GCLP_HCURSOR, (LONG_PTR)blank);
    LPARAM client = MAKELPARAM(HTCLIENT, WM_MOUSEMOVE);
    assert(guarded_DefWindowProcW(test_child, WM_SETCURSOR, (WPARAM)test_child, client) == TRUE);
    assert(cursor_calls == 1 && last_cursor == blank && default_calls == 0);
    /* Menu releases use LWJGL's null class cursor and must restore the arrow. */
    SetClassLongPtrW(test_child, GCLP_HCURSOR, 0);
    assert(guarded_DefWindowProcA(test_child, WM_SETCURSOR, (WPARAM)test_child, client) == TRUE);
    assert(cursor_calls == 2 && last_cursor == LoadCursorW(NULL, (LPCWSTR)IDC_ARROW));

    assert(guarded_DefWindowProcW(test_child, WM_SETCURSOR, 0, MAKELPARAM(HTCAPTION, WM_MOUSEMOVE)) == 123);
    assert(guarded_DefWindowProcA(test_child, WM_NULL, 0, 0) == 123);
    test_focus = root;
    assert(guarded_DefWindowProcW(test_child, WM_SETCURSOR, 0, client) == 123);
    test_focus = test_child;
    test_foreground = other;
    assert(guarded_DefWindowProcW(test_child, WM_SETCURSOR, 0, client) == 123);
    test_foreground = root;
    test_visible = 0;
    assert(guarded_DefWindowProcW(test_child, WM_SETCURSOR, 0, client) == 123);
    test_visible = 1;
    test_foreign = 1;
    assert(guarded_DefWindowProcW(test_child, WM_SETCURSOR, 0, client) == 123);
    assert(cursor_calls == 2 && default_calls == 6);

    DestroyWindow(root);
    DestroyWindow(other);
    DestroyCursor(blank);
    UnregisterClassW(cls.lpszClassName, cls.hInstance);
    puts("PASS legacy mouse: foreground child, focus loss, hidden/foreign targets, gameplay/menu cursors");
    return 0;
}
