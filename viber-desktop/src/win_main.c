/*
 * RemoteViber Native Windows Desktop Application
 * Win32 Native Implementation with Rounded Corners, Dark Mode, and Hardware Acceleration
 * Ultra-low resource usage, asynchronous & non-blocking.
 */

#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <shellapi.h>
#include <dwmapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _MSC_VER
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "dwmapi.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#endif

#ifndef DWMWA_USE_IMMERSIVE_DARK_MODE
#define DWMWA_USE_IMMERSIVE_DARK_MODE 20
#endif

#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif

#ifndef DWMWCP_ROUNDED
#define DWMWCP_ROUNDED 2
#endif

#define DEFAULT_PORT 8765
#define APP_TITLE L"RemoteViber 终端拼图工作台"
#define APP_CLASS L"RemoteViberDesktopWindowClass"

typedef struct {
    HWND hWnd;
    int port;
    wchar_t host[64];
    wchar_t target_url[512];
    wchar_t app_dir[MAX_PATH];
    HANDLE hHostProcess;
} WinAppState;

static WinAppState g_state;

/* Check if TCP port is listening on localhost non-blockingly */
static BOOL IsPortOpen(const wchar_t *host, int port) {
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        return FALSE;
    }

    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        WSACleanup();
        return FALSE;
    }

    u_long mode = 1; /* Non-blocking */
    ioctlsocket(sock, FIONBIO, &mode);

    char host_a[64];
    WideCharToMultiByte(CP_UTF8, 0, host, -1, host_a, sizeof(host_a), NULL, NULL);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((u_short)port);
    inet_pton(AF_INET, host_a, &addr.sin_addr);

    connect(sock, (struct sockaddr *)&addr, sizeof(addr));

    fd_set writeSet;
    FD_ZERO(&writeSet);
    FD_SET(sock, &writeSet);

    struct timeval timeout;
    timeout.tv_sec = 0;
    timeout.tv_usec = 200000; /* 200ms */

    BOOL isOpen = FALSE;
    if (select(0, NULL, &writeSet, NULL, &timeout) > 0) {
        if (FD_ISSET(sock, &writeSet)) {
            int err = 0;
            int errLen = sizeof(err);
            getsockopt(sock, SOL_SOCKET, SO_ERROR, (char *)&err, &errLen);
            isOpen = (err == 0);
        }
    }

    closesocket(sock);
    WSACleanup();
    return isOpen;
}

/* Locate Edge / Chrome executable on Windows */
static BOOL FindBrowserEngine(wchar_t *outPath, size_t maxLen) {
    wchar_t localAppData[MAX_PATH];
    wchar_t progFiles[MAX_PATH];
    wchar_t progFilesX86[MAX_PATH];

    GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, MAX_PATH);
    GetEnvironmentVariableW(L"ProgramFiles", progFiles, MAX_PATH);
    GetEnvironmentVariableW(L"ProgramFiles(x86)", progFilesX86, MAX_PATH);

    const wchar_t *candidates[] = {
        /* Microsoft Edge (Pre-installed on Win 10 / 11) */
        L"%s\\Microsoft\\Edge\\Application\\msedge.exe",
        L"%s\\Google\\Chrome\\Application\\chrome.exe",
        NULL
    };

    const wchar_t *roots[] = { progFilesX86, progFiles, localAppData, NULL };

    for (int c = 0; candidates[c] != NULL; c++) {
        for (int r = 0; roots[r] != NULL; r++) {
            if (roots[r][0] == L'\0') continue;
            wchar_t testPath[MAX_PATH];
            swprintf(testPath, MAX_PATH, candidates[c], roots[r]);
            if (GetFileAttributesW(testPath) != INVALID_FILE_ATTRIBUTES) {
                wcsncpy(outPath, testPath, maxLen - 1);
                outPath[maxLen - 1] = L'\0';
                return TRUE;
            }
        }
    }

    return FALSE;
}

static HANDLE g_hJob = NULL;

static void SetupJobObject(HANDLE hProcess) {
    if (!g_hJob) {
        g_hJob = CreateJobObjectW(NULL, NULL);
        if (g_hJob) {
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION jeli;
            ZeroMemory(&jeli, sizeof(jeli));
            jeli.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            SetInformationJobObject(g_hJob, JobObjectExtendedLimitInformation, &jeli, sizeof(jeli));
        }
    }
    if (g_hJob && hProcess) {
        AssignProcessToJobObject(g_hJob, hProcess);
    }
}

/* Asynchronously spawn local host service if needed */
static BOOL SpawnLocalHost(WinAppState *state) {
    wchar_t exeCandidates[2][MAX_PATH];
    swprintf(exeCandidates[0], MAX_PATH, L"%s\\viber-host-windows.exe", state->app_dir);
    swprintf(exeCandidates[1], MAX_PATH, L"%s\\viber-host.exe", state->app_dir);

    for (int i = 0; i < 2; i++) {
        if (GetFileAttributesW(exeCandidates[i]) != INVALID_FILE_ATTRIBUTES) {
            wchar_t cmdLine[MAX_PATH + 32];
            swprintf(cmdLine, MAX_PATH + 32, L"\"%s\" --port %d", exeCandidates[i], state->port);

            STARTUPINFOW si;
            PROCESS_INFORMATION pi;
            ZeroMemory(&si, sizeof(si));
            si.cb = sizeof(si);
            ZeroMemory(&pi, sizeof(pi));

            if (CreateProcessW(NULL, cmdLine, NULL, NULL, FALSE, CREATE_NO_WINDOW | DETACHED_PROCESS, NULL, NULL, &si, &pi)) {
                state->hHostProcess = pi.hProcess;
                SetupJobObject(pi.hProcess);
                CloseHandle(pi.hThread);
                return TRUE;
            }
        }
    }

    return FALSE;
}

/* Launch standalone desktop window and wait for completion to clean up background host */
static BOOL LaunchDesktopWindow(WinAppState *state) {
    wchar_t browserPath[MAX_PATH] = {0};
    if (FindBrowserEngine(browserPath, MAX_PATH)) {
        wchar_t localAppData[MAX_PATH];
        GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, MAX_PATH);

        wchar_t userDataDir[MAX_PATH];
        swprintf(userDataDir, MAX_PATH, L"%s\\RemoteViber\\profile", localAppData);
        CreateDirectoryW(userDataDir, NULL);

        wchar_t args[2048];
        swprintf(args, 2048,
            L"--app=\"%s\" "
            L"--window-size=1360,860 "
            L"--user-data-dir=\"%s\" "
            L"--disable-sync "
            L"--disable-background-networking "
            L"--enable-features=OverlayScrollbar "
            L"--force-dark-mode",
            state->target_url, userDataDir);

        SHELLEXECUTEINFOW sei;
        ZeroMemory(&sei, sizeof(sei));
        sei.cbSize = sizeof(sei);
        sei.fMask = SEE_MASK_DOENVSUBST | SEE_MASK_NOCLOSEPROCESS;
        sei.lpVerb = L"open";
        sei.lpFile = browserPath;
        sei.lpParameters = args;
        sei.nShow = SW_SHOWNORMAL;

        if (ShellExecuteExW(&sei)) {
            if (sei.hProcess) {
                WaitForSingleObject(sei.hProcess, INFINITE);
                CloseHandle(sei.hProcess);
            }
            if (state->hHostProcess) {
                TerminateProcess(state->hHostProcess, 0);
                CloseHandle(state->hHostProcess);
                state->hHostProcess = NULL;
            }
            if (g_hJob) {
                CloseHandle(g_hJob);
                g_hJob = NULL;
            }
            return TRUE;
        }
    }

    /* Fallback: default web browser */
    ShellExecuteW(NULL, L"open", state->target_url, NULL, NULL, SW_SHOWNORMAL);
    return TRUE;
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPWSTR lpCmdLine, int nCmdShow) {
    (void)hInstance;
    (void)hPrevInstance;
    (void)lpCmdLine;
    (void)nCmdShow;

    ZeroMemory(&g_state, sizeof(g_state));
    g_state.port = DEFAULT_PORT;
    wcscpy(g_state.host, L"127.0.0.1");

    /* Get directory of executable */
    wchar_t exePath[MAX_PATH];
    if (GetModuleFileNameW(NULL, exePath, MAX_PATH) > 0) {
        wchar_t *lastSlash = wcsrchr(exePath, L'\\');
        if (lastSlash) {
            *lastSlash = L'\0';
            wcscpy(g_state.app_dir, exePath);
        }
    }

    /* Parse command line arguments */
    int argc = 0;
    LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv) {
        for (int i = 1; i < argc; i++) {
            if (wcscmp(argv[i], L"--port") == 0 && i + 1 < argc) {
                g_state.port = _wtoi(argv[++i]);
            } else if (wcscmp(argv[i], L"--host") == 0 && i + 1 < argc) {
                wcsncpy(g_state.host, argv[++i], 63);
            } else if (wcscmp(argv[i], L"--url") == 0 && i + 1 < argc) {
                wcsncpy(g_state.target_url, argv[++i], 511);
            } else if (wcscmp(argv[i], L"--help") == 0 || wcscmp(argv[i], L"-h") == 0) {
                MessageBoxW(NULL,
                    L"RemoteViber 终端拼图工作台 (Windows Native App)\n\n"
                    L"用法:\n"
                    L"  RemoteViber.exe [选项]\n\n"
                    L"选项:\n"
                    L"  --port <端口>    指定本地服务端口 (默认: 8765)\n"
                    L"  --host <地址>    指定本地服务地址 (默认: 127.0.0.1)\n"
                    L"  --url <URL>      直接指定连接目标 URL\n"
                    L"  --help, -h       显示此帮助对话框",
                    L"RemoteViber 帮助", MB_OK | MB_ICONINFORMATION);
                LocalFree(argv);
                return 0;
            }
        }
        LocalFree(argv);
    }

    if (g_state.target_url[0] == L'\0') {
        swprintf(g_state.target_url, 512, L"http://%s:%d/", g_state.host, g_state.port);
    }

    /* Check if host is running; if not, asynchronously spawn it */
    if (!IsPortOpen(g_state.host, g_state.port)) {
        SpawnLocalHost(&g_state);
        /* Allow up to 1.5 seconds for daemon to initialize port */
        for (int i = 0; i < 15; i++) {
            Sleep(100);
            if (IsPortOpen(g_state.host, g_state.port)) {
                break;
            }
        }
    }

    /* Launch standalone desktop window */
    LaunchDesktopWindow(&g_state);

    return 0;
}
