/*
 * RemoteViber Native Linux Desktop Client
 * Powered by GTK+ 3.0 & WebKit2GTK 4.1
 * Completely decoupled from the server; client and server run independently.
 * Ultra-low resource usage, smooth animations, asynchronous & non-blocking.
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
    
    gboolean is_fullscreen;
    gboolean page_loaded;
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

/* Polling callback: wait for host server to become available */
static gboolean check_host_ready_cb(gpointer user_data) {
    AppState *state = (AppState *)user_data;

    if (is_port_open(state->host, state->port)) {
        g_print("[RemoteViber] 服务端已连接 (http://%s:%d/)，正在加载工作台...\n", state->host, state->port);
        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='small' foreground='#38bdf8'>已检测到服务端，正在加载终端拼图网格...</span>");
        
        char status_str[128];
        snprintf(status_str, sizeof(status_str),
            "<span color='#10b981' font_weight='bold'>●</span> <span color='#94a3b8' font_size='small'>%d 准备中</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->status_label), status_str);

        /* Load URL; transition to webview once WEBKIT_LOAD_FINISHED fires */
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(state->web_view), state->target_url);
        state->poll_timer_id = 0;
        return G_SOURCE_REMOVE;
    }

    /* Server not reachable yet: update waiting screen */
    char wait_msg[1024];
    snprintf(wait_msg, sizeof(wait_msg),
        "<span size='medium' weight='bold' foreground='#38bdf8'>正在等待连接服务端 (http://%s:%d/)...</span>\n\n"
        "<span size='small' foreground='#94a3b8'>客户端与服务端已脱离，请在终端独立启动服务端：</span>\n"
        "<span font_family='monospace' size='small' foreground='#34d399'>  ./dist-bin/viber-host-linux-x86_64 --port %d  </span>\n\n"
        "<span size='small' foreground='#64748b'>（服务端启动后客户端将自动感应并呈现终端工作台）</span>",
        state->host, state->port, state->port);
    gtk_label_set_markup(GTK_LABEL(state->spinner_label), wait_msg);

    char badge_str[128];
    snprintf(badge_str, sizeof(badge_str),
        "<span color='#f59e0b' font_weight='bold'>○</span> <span color='#94a3b8' font_size='small'>等待服务 (%d)</span>", state->port);
    gtk_label_set_markup(GTK_LABEL(state->status_label), badge_str);

    return G_SOURCE_CONTINUE;
}

/* Retry / Reconnect button handler */
static void on_retry_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    gtk_widget_hide(state->retry_btn);
    gtk_spinner_start(GTK_SPINNER(state->spinner));
    if (state->poll_timer_id == 0) {
        state->poll_timer_id = g_timeout_add(1000, check_host_ready_cb, state);
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
    g_printerr("[RemoteViber] 连接断开或页面加载失败: %s (原因: %s)\n", failing_uri, error ? error->message : "无法连接");

    state->page_loaded = FALSE;
    gtk_stack_set_visible_child(GTK_STACK(state->stack), state->loading_box);
    gtk_spinner_start(GTK_SPINNER(state->spinner));

    char err_msg[1024];
    snprintf(err_msg, sizeof(err_msg),
        "<span size='medium' weight='bold' foreground='#ef4444'>与服务端的连接已断开</span>\n\n"
        "<span size='small' foreground='#94a3b8'>请确认服务端正在独立运行：</span>\n"
        "<span font_family='monospace' size='small' foreground='#34d399'>  ./dist-bin/viber-host-linux-x86_64 --port %d  </span>\n\n"
        "<span size='small' foreground='#64748b'>正在自动检测重新连接...</span>",
        state->port);
    gtk_label_set_markup(GTK_LABEL(state->spinner_label), err_msg);

    char badge_str[128];
    snprintf(badge_str, sizeof(badge_str),
        "<span color='#ef4444' font_weight='bold'>✕</span> <span color='#94a3b8' font_size='small'>已断开 (%d)</span>", state->port);
    gtk_label_set_markup(GTK_LABEL(state->status_label), badge_str);

    /* Resume polling to auto-reconnect when server is restarted */
    if (state->poll_timer_id == 0) {
        state->poll_timer_id = g_timeout_add(1000, check_host_ready_cb, state);
    }

    return TRUE; /* Handled */
}

/* WebKit WebProcess crash handler */
static void on_web_process_terminated(WebKitWebView *web_view, WebKitWebProcessTerminationReason reason, gpointer user_data) {
    AppState *state = (AppState *)user_data;
    g_printerr("[RemoteViber] 网页渲染进程异常退出 (原因代码: %d)，正在自动重连...\n", reason);

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

    /* Ctrl+Q: Close client window (does not affect standalone server) */
    if ((event->state & GDK_CONTROL_MASK) && (event->keyval == GDK_KEY_q || event->keyval == GDK_KEY_Q)) {
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
        if (is_port_open(state->host, state->port)) {
            webkit_web_view_reload(WEBKIT_WEB_VIEW(state->web_view));
        } else {
            on_load_failed(WEBKIT_WEB_VIEW(state->web_view), WEBKIT_LOAD_FINISHED, state->target_url, NULL, state);
        }
        return TRUE;
    }

    /* All other keys pass through to WebKitWebView for Mosaic Terminal handling (Alt+Arrow, Alt+1~4, Alt+M, Alt+K, etc.) */
    return FALSE;
}

/* Header bar action button callbacks */
static void on_reload_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    if (is_port_open(state->host, state->port)) {
        webkit_web_view_reload(WEBKIT_WEB_VIEW(state->web_view));
    } else {
        on_load_failed(WEBKIT_WEB_VIEW(state->web_view), WEBKIT_LOAD_FINISHED, state->target_url, NULL, state);
    }
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
    gtk_window_close(GTK_WINDOW(state->window));
}

/* Clean exit handler: closes client only; server is independent */
static void on_window_destroy(GtkWidget *widget, gpointer user_data) {
    (void)widget;
    (void)user_data;
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
        } else if (strcmp(argv[i], "--no-gpu") == 0 || strcmp(argv[i], "--software") == 0) {
            force_software_rendering = TRUE;
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            g_print("RemoteViber 原生桌面工作台 (Linux Native Desktop Client)\n"
                    "基于 GTK+ 3.0 & WebKit2GTK 4.1 原生图形库打造 (客户端与服务端完全独立解耦)\n\n"
                    "用法: viber-desktop-linux [选项]\n\n"
                    "选项:\n"
                    "  --port <port>   指定服务端连接端口 (默认: 8765)\n"
                    "  --host <ip>     指定服务端连接IP (默认: 127.0.0.1)\n"
                    "  --url <url>     直接指定连接完整 URL (例如 http://192.168.1.100:8765/)\n"
                    "  --no-gpu        强制使用 CPU 纯软件渲染 (兼容老旧驱动/虚拟机)\n"
                    "  --help, -h      显示帮助信息\n\n"
                    "说明:\n"
                    "  客户端不再自动拉起后台服务；请在终端独立启动服务端:\n"
                    "    ./dist-bin/viber-host-linux-x86_64 --port 8765\n");
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
    char init_badge[128];
    snprintf(init_badge, sizeof(init_badge),
        "<span color='#f59e0b' font_weight='bold'>○</span> <span color='#94a3b8' font_size='small'>检测服务 (%d)</span>", app_state.port);
    gtk_label_set_markup(GTK_LABEL(app_state.status_label), init_badge);
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
        "<span size='small' foreground='#64748b'>高弹性 · 磁吸吸附拼图 · 原生独立客户端</span>");

    app_state.spinner = gtk_spinner_new();
    gtk_widget_set_size_request(app_state.spinner, 42, 42);
    gtk_spinner_start(GTK_SPINNER(app_state.spinner));

    app_state.spinner_label = gtk_label_new(NULL);
    char initial_wait_text[1024];
    snprintf(initial_wait_text, sizeof(initial_wait_text),
        "<span size='medium' weight='bold' foreground='#38bdf8'>正在连接服务端 (http://%s:%d/)...</span>\n\n"
        "<span size='small' foreground='#94a3b8'>若服务端尚未启动，请在独立终端中执行：</span>\n"
        "<span font_family='monospace' size='small' foreground='#34d399'>  ./dist-bin/viber-host-linux-x86_64 --port %d  </span>",
        app_state.host, app_state.port, app_state.port);
    gtk_label_set_markup(GTK_LABEL(app_state.spinner_label), initial_wait_text);

    app_state.retry_btn = gtk_button_new_with_label("立即重试连接");
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

    /* Start non-blocking polling timer for host availability (polling every 1 second) */
    app_state.poll_timer_id = g_timeout_add(1000, check_host_ready_cb, &app_state);

    /* Trigger immediate initial check */
    check_host_ready_cb(&app_state);

    gtk_main();

    return 0;
}
