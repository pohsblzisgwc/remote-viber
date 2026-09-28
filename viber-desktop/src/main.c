/*
 * RemoteViber Native Linux Desktop Application
 * Powered by GTK+ 3.0 & WebKit2GTK 4.1
 * High elasticity, smooth animations, ultra-low resource usage, asynchronous & non-blocking.
 */

#define _GNU_SOURCE
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <libgen.h>

#define DEFAULT_HOST "127.0.0.1"
#define DEFAULT_PORT 8765
#define APP_TITLE "RemoteViber 终端拼图工作台"
#define APP_ID "com.remoteviber.desktop"

typedef struct {
    GtkWidget *window;
    GtkWidget *header_bar;
    GtkWidget *status_badge;
    GtkWidget *status_label;
    GtkWidget *stack;
    GtkWidget *loading_box;
    GtkWidget *spinner;
    GtkWidget *spinner_title;
    GtkWidget *spinner_label;
    GtkWidget *retry_btn;
    GtkWidget *web_view;
    WebKitWebInspector *inspector;
    
    char host[128];
    int port;
    char target_url[512];
    char app_dir[1024];
    
    pid_t spawned_host_pid;
    gboolean is_fullscreen;
    gboolean keep_host_on_exit;
    gboolean page_loaded;
    int connect_attempts;
    guint poll_timer_id;
} AppState;

static AppState app_state;

/* Check if TCP socket connects non-blockingly */
static gboolean is_port_open(const char *ip, int port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return FALSE;

    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, ip, &addr.sin_addr);

    int res = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
    if (res == 0) {
        close(sock);
        return TRUE;
    }

    if (errno == EINPROGRESS) {
        fd_set set;
        FD_ZERO(&set);
        FD_SET(sock, &set);
        struct timeval tv = { .tv_sec = 0, .tv_usec = 250000 }; /* 250ms */
        if (select(sock + 1, NULL, &set, NULL, &tv) > 0) {
            int so_error = 0;
            socklen_t len = sizeof(so_error);
            getsockopt(sock, SOL_SOCKET, SO_ERROR, &so_error, &len);
            close(sock);
            return (so_error == 0);
        }
    }

    close(sock);
    return FALSE;
}

/* Gracefully terminate spawned host process and its entire process group */
static void stop_spawned_host(AppState *state) {
    if (state->keep_host_on_exit) {
        g_print("[RemoteViber] 配置为保留后台服务 (--keep-host)，跳过关闭。\n");
        return;
    }
    if (state->spawned_host_pid > 0) {
        pid_t pid = state->spawned_host_pid;
        state->spawned_host_pid = 0;
        g_print("[RemoteViber] 正在关闭后台服务进程 (PID: %d)...\n", pid);

        /* Send SIGTERM to the process group so all child workers receive it */
        kill(-pid, SIGTERM);
        kill(pid, SIGTERM);

        /* Wait up to 1.5 seconds for graceful shutdown */
        for (int i = 0; i < 15; i++) {
            int status = 0;
            pid_t r = waitpid(pid, &status, WNOHANG);
            if (r == pid || r == -1) {
                g_print("[RemoteViber] 后台服务已成功安全退出。\n");
                return;
            }
            g_usleep(100000); /* 100ms */
        }

        /* If still alive after 1.5s, forcefully terminate */
        g_print("[RemoteViber] 服务未在时限内退出，执行强制终止 (SIGKILL)...\n");
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        int status = 0;
        waitpid(pid, &status, 0);
        g_print("[RemoteViber] 后台服务已清理完毕。\n");
    }
}

/* Global signal handler for SIGINT, SIGTERM, SIGHUP */
static void signal_handler(int sig) {
    (void)sig;
    stop_spawned_host(&app_state);
    _exit(0);
}

/* Locate and asynchronously spawn viber-host if not running */
static gboolean try_spawn_host(AppState *state) {
    const char *candidates[] = {
        "./dist-bin/viber-host-linux-x86_64",
        "../dist-bin/viber-host-linux-x86_64",
        "./viber-host-linux-x86_64",
        "/usr/local/bin/viber-host-linux-x86_64",
        NULL
    };

    char resolved_path[2048] = {0};
    for (int i = 0; candidates[i] != NULL; i++) {
        if (access(candidates[i], X_OK) == 0) {
            snprintf(resolved_path, sizeof(resolved_path), "%s", candidates[i]);
            break;
        }
    }

    /* Check relative to executable dir */
    if (resolved_path[0] == '\0' && state->app_dir[0] != '\0') {
        char test_path[2048];
        snprintf(test_path, sizeof(test_path), "%s/viber-host-linux-x86_64", state->app_dir);
        if (access(test_path, X_OK) == 0) {
            snprintf(resolved_path, sizeof(resolved_path), "%s", test_path);
        } else {
            snprintf(test_path, sizeof(test_path), "%s/../dist-bin/viber-host-linux-x86_64", state->app_dir);
            if (access(test_path, X_OK) == 0) {
                snprintf(resolved_path, sizeof(resolved_path), "%s", test_path);
            }
        }
    }

    if (resolved_path[0] != '\0') {
        g_print("[RemoteViber] 正在后台启动原生服务: %s\n", resolved_path);
        pid_t pid = fork();
        if (pid == 0) {
            /* Child process: become process group leader */
            setpgid(0, 0);
#ifdef __linux__
            /* Automatically receive SIGTERM if parent process dies */
            prctl(PR_SET_PDEATHSIG, SIGTERM);
#endif
            int devnull = open("/dev/null", O_RDWR);
            if (devnull >= 0) {
                dup2(devnull, STDIN_FILENO);
                dup2(devnull, STDOUT_FILENO);
                dup2(devnull, STDERR_FILENO);
                close(devnull);
            }
            char port_str[16];
            snprintf(port_str, sizeof(port_str), "%d", state->port);
            char *args[] = { resolved_path, "--port", port_str, NULL };
            execv(resolved_path, args);
            _exit(1);
        } else if (pid > 0) {
            state->spawned_host_pid = pid;
            return TRUE;
        }
    }

    /* Fallback: try python3 viber-host/main.py */
    const char *py_candidates[] = {
        "viber-host/main.py",
        "../viber-host/main.py",
        NULL
    };
    for (int i = 0; py_candidates[i] != NULL; i++) {
        if (access(py_candidates[i], R_OK) == 0) {
            g_print("[RemoteViber] 启动 Python 核心服务: %s\n", py_candidates[i]);
            pid_t pid = fork();
            if (pid == 0) {
                setpgid(0, 0);
#ifdef __linux__
                prctl(PR_SET_PDEATHSIG, SIGTERM);
#endif
                int devnull = open("/dev/null", O_RDWR);
                if (devnull >= 0) {
                    dup2(devnull, STDIN_FILENO);
                    dup2(devnull, STDOUT_FILENO);
                    dup2(devnull, STDERR_FILENO);
                    close(devnull);
                }
                char port_str[16];
                snprintf(port_str, sizeof(port_str), "%d", state->port);
                char *args[] = { (char *)"python3", (char *)py_candidates[i], (char *)"--port", port_str, NULL };
                execvp("python3", args);
                _exit(1);
            } else if (pid > 0) {
                state->spawned_host_pid = pid;
                return TRUE;
            }
        }
    }

    return FALSE;
}

/* Polling callback to check when host becomes ready */
static gboolean check_host_ready_cb(gpointer user_data) {
    AppState *state = (AppState *)user_data;
    state->connect_attempts++;

    if (is_port_open(state->host, state->port)) {
        g_print("[RemoteViber] 本地服务就绪 (127.0.0.1:%d)，正在加载终端工作台...\n", state->port);
        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='small' foreground='#38bdf8'>本地服务已连接，正在加载终端网格...</span>");
        
        char status_str[128];
        snprintf(status_str, sizeof(status_str),
            "<span color='#10b981' font_weight='bold'>●</span> <span color='#94a3b8' font_size='small'>%d 准备中</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->status_label), status_str);

        /* Start loading URL into WebKit; transition stack only on WEBKIT_LOAD_FINISHED to prevent black screen! */
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(state->web_view), state->target_url);
        state->poll_timer_id = 0;
        return G_SOURCE_REMOVE;
    }

    if (state->connect_attempts == 1) {
        try_spawn_host(state);
    }

    if (state->connect_attempts > 60) { /* 15 seconds */
        gtk_spinner_stop(GTK_SPINNER(state->spinner));
        char err_msg[384];
        snprintf(err_msg, sizeof(err_msg),
            "<span size='small' foreground='#ef4444'>无法连接到本地服务 (127.0.0.1:%d)</span>\n"
            "<span size='small' foreground='#64748b'>请检查端口是否被占用，或点击下方重新尝试启动。</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->spinner_label), err_msg);
        gtk_widget_show(state->retry_btn);

        gtk_label_set_markup(GTK_LABEL(state->status_label),
            "<span color='#ef4444' font_weight='bold'>✕</span> <span color='#94a3b8' font_size='small'>连接失败</span>");
        state->poll_timer_id = 0;
        return G_SOURCE_REMOVE;
    }

    return G_SOURCE_CONTINUE;
}

/* Retry button handler */
static void on_retry_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    gtk_widget_hide(state->retry_btn);
    gtk_label_set_markup(GTK_LABEL(state->spinner_label),
        "<span size='small' foreground='#94a3b8'>正在重新尝试唤醒本地服务...</span>");
    gtk_spinner_start(GTK_SPINNER(state->spinner));
    state->connect_attempts = 0;
    if (state->poll_timer_id == 0) {
        state->poll_timer_id = g_timeout_add(250, check_host_ready_cb, state);
    }
}

/* WebKit load-changed handler: only switch view when page has actually loaded! */
static void on_load_changed(WebKitWebView *web_view, WebKitLoadEvent event, gpointer user_data) {
    (void)web_view;
    AppState *state = (AppState *)user_data;

    if (event == WEBKIT_LOAD_COMMITTED || event == WEBKIT_LOAD_FINISHED) {
        state->page_loaded = TRUE;
        gtk_spinner_stop(GTK_SPINNER(state->spinner));
        gtk_stack_set_visible_child(GTK_STACK(state->stack), state->web_view);
        
        char status_str[128];
        snprintf(status_str, sizeof(status_str),
            "<span color='#10b981' font_weight='bold'>●</span> <span color='#94a3b8' font_size='small'>%d 在线</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->status_label), status_str);
    }
}

/* WebKit load-failed handler */
static gboolean on_load_failed(WebKitWebView *web_view, WebKitLoadEvent event, gchar *failing_uri, GError *error, gpointer user_data) {
    (void)web_view; (void)event;
    AppState *state = (AppState *)user_data;
    g_printerr("[RemoteViber] 页面加载失败: %s (错误: %s)\n", failing_uri, error ? error->message : "未知错误");

    gtk_stack_set_visible_child(GTK_STACK(state->stack), state->loading_box);
    gtk_spinner_stop(GTK_SPINNER(state->spinner));

    char err_msg[512];
    snprintf(err_msg, sizeof(err_msg),
        "<span size='small' foreground='#ef4444'>页面加载失败: %s</span>\n"
        "<span size='small' foreground='#64748b'>请确认服务正在正常运行或点击下方重试</span>",
        error ? error->message : "无法连接到本地服务");
    gtk_label_set_markup(GTK_LABEL(state->spinner_label), err_msg);
    gtk_widget_show(state->retry_btn);

    return TRUE; /* Handled */
}

/* WebKit WebProcess crash handler */
static void on_web_process_terminated(WebKitWebView *web_view, WebKitWebProcessTerminationReason reason, gpointer user_data) {
    AppState *state = (AppState *)user_data;
    g_printerr("[RemoteViber] 网页渲染进程异常退出 (原因代码: %d)，正在自动重启...\n", reason);

    gtk_stack_set_visible_child(GTK_STACK(state->stack), state->loading_box);
    gtk_label_set_markup(GTK_LABEL(state->spinner_label),
        "<span size='small' foreground='#f59e0b'>网页渲染进程正在自动恢复，请稍候...</span>");
    gtk_spinner_start(GTK_SPINNER(state->spinner));

    webkit_web_view_reload(web_view);
}

/* CSS Theme for Sleek Dark Glass UI */
static void apply_dark_theme(void) {
    GtkCssProvider *provider = gtk_css_provider_new();
    const char *css =
        "window.remote-viber-window {"
        "  background-color: #090d16;"
        "  color: #f1f5f9;"
        "}"
        "headerbar.remote-viber-header {"
        "  background-color: #0b0f19;"
        "  border-bottom: 1px solid rgba(255, 255, 255, 0.08);"
        "  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.35);"
        "  padding: 4px 10px;"
        "  min-height: 44px;"
        "}"
        ".status-pill {"
        "  background-color: rgba(15, 23, 42, 0.7);"
        "  border: 1px solid rgba(255, 255, 255, 0.1);"
        "  border-radius: 9999px;"
        "  padding: 3px 10px;"
        "}"
        ".action-btn {"
        "  background-color: rgba(30, 41, 59, 0.6);"
        "  border: 1px solid rgba(255, 255, 255, 0.08);"
        "  border-radius: 8px;"
        "  color: #94a3b8;"
        "  padding: 4px 10px;"
        "  margin: 0 2px;"
        "  transition: all 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);"
        "}"
        ".action-btn:hover {"
        "  background-color: rgba(51, 65, 85, 0.8);"
        "  color: #38bdf8;"
        "  border-color: rgba(56, 189, 248, 0.3);"
        "}"
        ".action-btn:active {"
        "  background-color: rgba(56, 189, 248, 0.2);"
        "}"
        ".quit-btn:hover {"
        "  background-color: rgba(239, 68, 68, 0.2);"
        "  color: #ef4444;"
        "  border-color: rgba(239, 68, 68, 0.3);"
        "}"
        ".retry-btn {"
        "  background-color: #0284c7;"
        "  color: #ffffff;"
        "  border-radius: 8px;"
        "  padding: 6px 16px;"
        "  font-weight: bold;"
        "}"
        ".retry-btn:hover {"
        "  background-color: #0369a1;"
        "}";

    gtk_css_provider_load_from_data(provider, css, -1, NULL);
    gtk_style_context_add_provider_for_screen(
        gdk_screen_get_default(),
        GTK_STYLE_PROVIDER(provider),
        GTK_STYLE_PROVIDER_PRIORITY_APPLICATION
    );
    g_object_unref(provider);
}

/* Dispatch JavaScript into WebKit view */
static void dispatch_web_js(WebKitWebView *web_view, const char *js) {
#if WEBKIT_CHECK_VERSION(2, 40, 0)
    webkit_web_view_evaluate_javascript(web_view, js, -1, NULL, NULL, NULL, NULL, NULL);
#else
    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    webkit_web_view_run_javascript(web_view, js, NULL, NULL, NULL);
    G_GNUC_END_IGNORE_DEPRECATIONS
#endif
}

/* Keyboard shortcuts handler */
static gboolean on_key_press(GtkWidget *widget, GdkEventKey *event, gpointer user_data) {
    (void)widget;
    AppState *state = (AppState *)user_data;

    /* Ctrl+Q: Clean exit and stop host */
    if ((event->state & GDK_CONTROL_MASK) && (event->keyval == GDK_KEY_q || event->keyval == GDK_KEY_Q)) {
        stop_spawned_host(state);
        gtk_window_close(GTK_WINDOW(state->window));
        return TRUE;
    }

    /* F11: Fullscreen toggle */
    if (event->keyval == GDK_KEY_F11) {
        if (state->is_fullscreen) {
            gtk_window_unfullscreen(GTK_WINDOW(state->window));
            state->is_fullscreen = FALSE;
        } else {
            gtk_window_fullscreen(GTK_WINDOW(state->window));
            state->is_fullscreen = TRUE;
        }
        return TRUE;
    }

    /* F12 or Ctrl+Shift+I: Web Inspector */
    if (event->keyval == GDK_KEY_F12 || 
        ((event->state & GDK_CONTROL_MASK) && (event->state & GDK_SHIFT_MASK) && (event->keyval == GDK_KEY_I || event->keyval == GDK_KEY_i))) {
        if (state->inspector) {
            webkit_web_inspector_show(state->inspector);
            return TRUE;
        }
    }

    /* Ctrl+R or F5: Reload */
    if ((event->keyval == GDK_KEY_F5) ||
        ((event->state & GDK_CONTROL_MASK) && (event->keyval == GDK_KEY_r || event->keyval == GDK_KEY_R))) {
        webkit_web_view_reload(WEBKIT_WEB_VIEW(state->web_view));
        return TRUE;
    }

    /* All other keys pass through to WebKitWebView for Mosaic Terminal handling (Alt+Arrow, Alt+1~4, Alt+M, Alt+K, etc.) */
    return FALSE;
}

/* Header bar action button callbacks */
static void on_reload_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    webkit_web_view_reload(WEBKIT_WEB_VIEW(state->web_view));
}

static void on_fullscreen_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    if (state->is_fullscreen) {
        gtk_window_unfullscreen(GTK_WINDOW(state->window));
        state->is_fullscreen = FALSE;
    } else {
        gtk_window_fullscreen(GTK_WINDOW(state->window));
        state->is_fullscreen = TRUE;
    }
}

static void on_split_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js = "window.dispatchEvent(new KeyboardEvent('keydown', { key: '\\\\', altKey: true, bubbles: true }));";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_mosaic_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js = "window.dispatchEvent(new KeyboardEvent('keydown', { key: 'm', altKey: true, bubbles: true }));";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_shortcuts_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js = "window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', altKey: true, bubbles: true }));";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_quit_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    stop_spawned_host(state);
    gtk_window_close(GTK_WINDOW(state->window));
}

/* Clean exit handler */
static void on_window_destroy(GtkWidget *widget, gpointer user_data) {
    (void)widget;
    AppState *state = (AppState *)user_data;
    stop_spawned_host(state);
    gtk_main_quit();
}

int main(int argc, char *argv[]) {
    memset(&app_state, 0, sizeof(app_state));
    strncpy(app_state.host, DEFAULT_HOST, sizeof(app_state.host) - 1);
    app_state.port = DEFAULT_PORT;

    /* Get directory of executable */
    char exe_path[1024];
    ssize_t len = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
    if (len != -1) {
        exe_path[len] = '\0';
        strncpy(app_state.app_dir, dirname(exe_path), sizeof(app_state.app_dir) - 1);
    }

    gboolean force_software_rendering = FALSE;

    /* Parse command line arguments */
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--port") == 0 && i + 1 < argc) {
            app_state.port = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--host") == 0 && i + 1 < argc) {
            strncpy(app_state.host, argv[++i], sizeof(app_state.host) - 1);
        } else if (strcmp(argv[i], "--url") == 0 && i + 1 < argc) {
            strncpy(app_state.target_url, argv[++i], sizeof(app_state.target_url) - 1);
        } else if (strcmp(argv[i], "--keep-host") == 0) {
            app_state.keep_host_on_exit = TRUE;
        } else if (strcmp(argv[i], "--no-gpu") == 0 || strcmp(argv[i], "--software") == 0) {
            force_software_rendering = TRUE;
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            g_print("RemoteViber 原生桌面工作台 (Linux Native App)\n"
                    "基于 GTK+ 3.0 & WebKit2GTK 4.1 原生图形库打造\n\n"
                    "用法: viber-desktop-linux [选项]\n\n"
                    "选项:\n"
                    "  --port <port>   指定服务端口 (默认: 8765)\n"
                    "  --host <ip>     指定服务IP (默认: 127.0.0.1)\n"
                    "  --url <url>     直接指定加载 URL\n"
                    "  --keep-host     退出应用时保留后台服务继续运行\n"
                    "  --no-gpu        强制使用 CPU 纯软件渲染 (避免 GPU 驱动黑屏)\n"
                    "  --help, -h      显示帮助信息\n");
            return 0;
        }
    }

    /* Prevent WebProcess bwrap sandbox permission failures in containers/unprivileged Linux accounts */
    if (!getenv("WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS")) {
        setenv("WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS", "1", 0);
    }
    if (!getenv("WEBKIT_FORCE_SANDBOX")) {
        setenv("WEBKIT_FORCE_SANDBOX", "0", 0);
    }

    /* If software rendering requested, disable compositing mode */
    if (force_software_rendering) {
        setenv("WEBKIT_DISABLE_COMPOSITING_MODE", "1", 1);
    }

    /* Register termination signals so background host is always cleaned up */
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGHUP, signal_handler);

    if (!gtk_init_check(&argc, &argv)) {
        g_printerr("[RemoteViber] 未检测到图形桌面环境 (DISPLAY 或 WAYLAND_DISPLAY 未设置)。\n"
                    "提示: 若在无显示器的服务器或 SSH 会话中，请直接运行控制端核心:\n"
                    "      ./dist-bin/viber-host-linux-x86_64 --port %d\n"
                    "并在外部浏览器中访问: http://<你的IP>:%d/\n",
                    app_state.port, app_state.port);
        return 1;
    }

    if (app_state.target_url[0] == '\0') {
        snprintf(app_state.target_url, sizeof(app_state.target_url), "http://%s:%d/", app_state.host, app_state.port);
    }

    apply_dark_theme();

    /* Create top-level GtkWindow */
    app_state.window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(app_state.window), APP_TITLE);
    gtk_window_set_default_size(GTK_WINDOW(app_state.window), 1360, 860);
    gtk_widget_set_size_request(app_state.window, 640, 480);
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.window), "remote-viber-window");

    /* Try to set application icon */
    const char *icon_paths[] = {
        "dist-bin/remote-viber.svg",
        "../dist-bin/remote-viber.svg",
        "/usr/share/icons/hicolor/scalable/apps/remote-viber.svg",
        NULL
    };
    for (int i = 0; icon_paths[i] != NULL; i++) {
        if (access(icon_paths[i], R_OK) == 0) {
            gtk_window_set_icon_from_file(GTK_WINDOW(app_state.window), icon_paths[i], NULL);
            break;
        }
    }

    /* Modern CSD HeaderBar */
    app_state.header_bar = gtk_header_bar_new();
    gtk_header_bar_set_show_close_button(GTK_HEADER_BAR(app_state.header_bar), TRUE);
    gtk_header_bar_set_title(GTK_HEADER_BAR(app_state.header_bar), "RemoteViber 拼图工作台");
    gtk_header_bar_set_subtitle(GTK_HEADER_BAR(app_state.header_bar), "4-Grid Mosaic Terminal | High Elasticity");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.header_bar), "remote-viber-header");

    /* Status Pill Badge */
    app_state.status_badge = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.status_badge), "status-pill");
    app_state.status_label = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(app_state.status_label),
        "<span color='#f59e0b' font_weight='bold'>○</span> <span color='#94a3b8' font_size='small'>启动服务中...</span>");
    gtk_box_pack_start(GTK_BOX(app_state.status_badge), app_state.status_label, FALSE, FALSE, 4);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), app_state.status_badge);

    /* Quick Action Buttons on HeaderBar */
    GtkWidget *btn_split = gtk_button_new_with_label("拼接 (Alt+\\)");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_split), "action-btn");
    g_signal_connect(btn_split, "clicked", G_CALLBACK(on_split_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), btn_split);

    GtkWidget *btn_mosaic = gtk_button_new_with_label("最大化 (Alt+M)");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_mosaic), "action-btn");
    g_signal_connect(btn_mosaic, "clicked", G_CALLBACK(on_mosaic_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), btn_mosaic);

    GtkWidget *btn_quit = gtk_button_new_with_label("退出 (Ctrl+Q)");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_quit), "action-btn");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_quit), "quit-btn");
    g_signal_connect(btn_quit, "clicked", G_CALLBACK(on_quit_clicked), &app_state);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(app_state.header_bar), btn_quit);

    GtkWidget *btn_fullscreen = gtk_button_new_with_label("全屏 (F11)");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_fullscreen), "action-btn");
    g_signal_connect(btn_fullscreen, "clicked", G_CALLBACK(on_fullscreen_clicked), &app_state);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(app_state.header_bar), btn_fullscreen);

    GtkWidget *btn_shortcuts = gtk_button_new_with_label("快捷键 (Alt+K)");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_shortcuts), "action-btn");
    g_signal_connect(btn_shortcuts, "clicked", G_CALLBACK(on_shortcuts_clicked), &app_state);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(app_state.header_bar), btn_shortcuts);

    GtkWidget *btn_reload = gtk_button_new_with_label("刷新 (Ctrl+R)");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_reload), "action-btn");
    g_signal_connect(btn_reload, "clicked", G_CALLBACK(on_reload_clicked), &app_state);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(app_state.header_bar), btn_reload);

    gtk_window_set_titlebar(GTK_WINDOW(app_state.window), app_state.header_bar);

    /* Main Stack Container */
    app_state.stack = gtk_stack_new();
    gtk_stack_set_transition_type(GTK_STACK(app_state.stack), GTK_STACK_TRANSITION_TYPE_CROSSFADE);
    gtk_stack_set_transition_duration(GTK_STACK(app_state.stack), 300);

    /* Loading / Splash View */
    app_state.loading_box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 14);
    gtk_widget_set_valign(app_state.loading_box, GTK_ALIGN_CENTER);
    gtk_widget_set_halign(app_state.loading_box, GTK_ALIGN_CENTER);

    app_state.spinner_title = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(app_state.spinner_title),
        "<span size='large' weight='bold' foreground='#38bdf8'>RemoteViber 终端拼图工作台</span>");
    
    GtkWidget *sub_lbl = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(sub_lbl),
        "<span size='small' foreground='#64748b'>高弹性 · 磁吸吸附拼图 · 零外部依赖原生桌面核心</span>");

    app_state.spinner = gtk_spinner_new();
    gtk_widget_set_size_request(app_state.spinner, 42, 42);
    gtk_spinner_start(GTK_SPINNER(app_state.spinner));

    app_state.spinner_label = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(app_state.spinner_label),
        "<span size='small' foreground='#94a3b8'>正在唤醒本地服务并加载终端网格...</span>");

    app_state.retry_btn = gtk_button_new_with_label("重新连接本地服务");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.retry_btn), "retry-btn");
    gtk_widget_set_no_show_all(app_state.retry_btn, TRUE);
    gtk_widget_hide(app_state.retry_btn);
    g_signal_connect(app_state.retry_btn, "clicked", G_CALLBACK(on_retry_clicked), &app_state);

    gtk_box_pack_start(GTK_BOX(app_state.loading_box), app_state.spinner_title, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(app_state.loading_box), sub_lbl, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(app_state.loading_box), app_state.spinner, FALSE, FALSE, 8);
    gtk_box_pack_start(GTK_BOX(app_state.loading_box), app_state.spinner_label, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(app_state.loading_box), app_state.retry_btn, FALSE, FALSE, 12);

    gtk_stack_add_named(GTK_STACK(app_state.stack), app_state.loading_box, "loading");

    /* WebKitWebView Setup (ON_DEMAND acceleration prevents black screen on Linux systems without DRI3) */
    WebKitSettings *settings = webkit_settings_new();
    webkit_settings_set_enable_webgl(settings, TRUE);
    webkit_settings_set_enable_smooth_scrolling(settings, TRUE);
    webkit_settings_set_enable_developer_extras(settings, TRUE);
    webkit_settings_set_enable_page_cache(settings, TRUE);
    webkit_settings_set_enable_javascript(settings, TRUE);
    webkit_settings_set_hardware_acceleration_policy(settings, WEBKIT_HARDWARE_ACCELERATION_POLICY_ON_DEMAND);

    app_state.web_view = webkit_web_view_new_with_settings(settings);
    g_object_unref(settings);

    app_state.inspector = webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(app_state.web_view));

    /* Connect WebKit events to safely transition and recover from errors */
    g_signal_connect(app_state.web_view, "load-changed", G_CALLBACK(on_load_changed), &app_state);
    g_signal_connect(app_state.web_view, "load-failed", G_CALLBACK(on_load_failed), &app_state);
    g_signal_connect(app_state.web_view, "web-process-terminated", G_CALLBACK(on_web_process_terminated), &app_state);

    gtk_stack_add_named(GTK_STACK(app_state.stack), app_state.web_view, "webview");
    gtk_container_add(GTK_CONTAINER(app_state.window), app_state.stack);

    /* Explicitly show loading view first */
    gtk_stack_set_visible_child_name(GTK_STACK(app_state.stack), "loading");

    /* Connect window signals */
    g_signal_connect(app_state.window, "key-press-event", G_CALLBACK(on_key_press), &app_state);
    g_signal_connect(app_state.window, "destroy", G_CALLBACK(on_window_destroy), &app_state);

    gtk_widget_show_all(app_state.window);

    /* Start non-blocking polling timer for host readiness */
    app_state.poll_timer_id = g_timeout_add(250, check_host_ready_cb, &app_state);

    gtk_main();

    return 0;
}
