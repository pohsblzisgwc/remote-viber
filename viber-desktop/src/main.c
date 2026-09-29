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
#include <netdb.h>

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
    gboolean is_loading;
    guint poll_timer_id;
} AppState;

static AppState app_state;

/* Check if TCP socket connects non-blockingly (supports IPv4, IPv6, hostnames) */
static gboolean is_port_open(const char *host, int port) {
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) {
        return FALSE;
    }

    int sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0) {
        freeaddrinfo(res);
        return FALSE;
    }

    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    int r = connect(sock, res->ai_addr, res->ai_addrlen);
    freeaddrinfo(res);

    if (r == 0) {
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

static gboolean check_host_ready_cb(gpointer user_data);

/* Helper to stop polling timer safely */
static void stop_poll_timer(AppState *state) {
    if (state->poll_timer_id != 0) {
        g_source_remove(state->poll_timer_id);
        state->poll_timer_id = 0;
    }
}

/* Helper to start polling timer safely without duplicates */
static void start_poll_timer(AppState *state, guint interval_ms) {
    stop_poll_timer(state);
    state->poll_timer_id = g_timeout_add(interval_ms, check_host_ready_cb, state);
}

/* Polling callback: wait for host server to become available */
static gboolean check_host_ready_cb(gpointer user_data) {
    AppState *state = (AppState *)user_data;

    if (state->page_loaded) {
        state->poll_timer_id = 0;
        return G_SOURCE_REMOVE;
    }

    if (state->is_loading) {
        /* Page load is already in progress; wait for load-changed or load-failed */
        return G_SOURCE_CONTINUE;
    }

    if (is_port_open(state->host, state->port)) {
        g_print("[RemoteViber] 服务端已连接 (%s)，正在加载工作台...\n", state->target_url);
        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='medium' weight='bold' foreground='#38bdf8'>已连接服务端！</span>\n\n"
            "<span size='small' foreground='#94a3b8'>正在载入终端拼图网格工作台...</span>");
        
        char status_str[128];
        snprintf(status_str, sizeof(status_str),
            "<span color='#10b981' font_weight='bold'>●</span> <span color='#38bdf8' font_size='small'>%d 准备中</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->status_label), status_str);

        state->is_loading = TRUE;
        state->poll_timer_id = 0;
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(state->web_view), state->target_url);
        return G_SOURCE_REMOVE;
    }

    /* Server not reachable yet: update waiting screen */
    char wait_msg[1024];
    snprintf(wait_msg, sizeof(wait_msg),
        "<span size='medium' weight='bold' foreground='#38bdf8'>正在等待连接服务端 (%s)...</span>\n\n"
        "<span size='small' foreground='#94a3b8'>客户端与服务端已独立脱离，请在终端独立启动服务端：</span>\n\n"
        "<span font_family='monospace' size='medium' foreground='#34d399'>  ./dist-bin/viber-host-linux-x86_64 --port %d  </span>\n\n"
        "<span size='small' foreground='#64748b'>服务端启动后客户端将毫秒级自动感应并呈现拼图终端</span>",
        state->target_url, state->port);
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
    gtk_spinner_start(GTK_SPINNER(state->spinner));
    state->page_loaded = FALSE;
    state->is_loading = FALSE;
    stop_poll_timer(state);
    if (check_host_ready_cb(state) == G_SOURCE_CONTINUE) {
        start_poll_timer(state, 1000);
    }
}

/* Open in system browser button handler */
static void on_open_browser_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    gtk_show_uri_on_window(GTK_WINDOW(state->window), state->target_url, GDK_CURRENT_TIME, NULL);
}

/* WebKit load-changed handler: only switch view when page has actually loaded! */
static void on_load_changed(WebKitWebView *web_view, WebKitLoadEvent event, gpointer user_data) {
    (void)web_view;
    AppState *state = (AppState *)user_data;

    if (event == WEBKIT_LOAD_COMMITTED) {
        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='medium' weight='bold' foreground='#38bdf8'>已建立通信连接</span>\n\n"
            "<span size='small' foreground='#94a3b8'>正在解析渲染拼图工作台视图...</span>");
    } else if (event == WEBKIT_LOAD_FINISHED) {
        state->page_loaded = TRUE;
        state->is_loading = FALSE;
        stop_poll_timer(state);
        gtk_spinner_stop(GTK_SPINNER(state->spinner));
        gtk_stack_set_visible_child(GTK_STACK(state->stack), state->web_view);
        
        char status_str[128];
        snprintf(status_str, sizeof(status_str),
            "<span color='#10b981' font_weight='bold'>●</span> <span color='#38bdf8' font_size='small'>%d 在线</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->status_label), status_str);
    }
}

/* WebKit load-failed handler */
static gboolean on_load_failed(WebKitWebView *web_view, WebKitLoadEvent event, gchar *failing_uri, GError *error, gpointer user_data) {
    (void)web_view; (void)event;
    AppState *state = (AppState *)user_data;

    /* Ignore benign cancellation errors (e.g. rapid reload, user navigation abort, redirect) */
    if (error) {
        if (g_error_matches(error, WEBKIT_NETWORK_ERROR, WEBKIT_NETWORK_ERROR_CANCELLED) ||
            g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE) ||
            g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_CANNOT_SHOW_MIME_TYPE) ||
            (error->message && (strstr(error->message, "cancelled") != NULL || strstr(error->message, "canceled") != NULL))) {
            g_print("[RemoteViber] 加载请求已取消（无害并忽略）: %s\n", failing_uri ? failing_uri : "");
            state->is_loading = FALSE;
            return TRUE;
        }
    }

    /* If the page is already loaded and host is still up, do not tear down the UI for subresource failures */
    if (state->page_loaded && is_port_open(state->host, state->port)) {
        g_printerr("[RemoteViber] 页面子资源加载告警: %s (原因: %s)\n", failing_uri ? failing_uri : "", error ? error->message : "未知");
        state->is_loading = FALSE;
        return TRUE;
    }

    g_printerr("[RemoteViber] 连接断开或页面加载失败: %s (原因: %s)\n", failing_uri ? failing_uri : "", error ? error->message : "无法连接");

    state->page_loaded = FALSE;
    state->is_loading = FALSE;
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
    start_poll_timer(state, 1000);

    return TRUE; /* Handled */
}

/* WebKit load-failed-with-tls-errors handler: allow self-signed TLS certificates for LAN/remote viber host */
static gboolean on_load_failed_with_tls_errors(WebKitWebView *web_view, gchar *failing_uri,
                                               GTlsCertificate *certificate, GTlsCertificateFlags errors,
                                               gpointer user_data) {
    (void)errors;
    AppState *state = (AppState *)user_data;
    g_print("[RemoteViber] 允许自签名或局域网 TLS 证书: %s\n", failing_uri ? failing_uri : "unknown");

    char host[256] = {0};
    if (failing_uri) {
        GUri *parsed = g_uri_parse(failing_uri, G_URI_FLAGS_NONE, NULL);
        if (parsed) {
            const char *h = g_uri_get_host(parsed);
            if (h) strncpy(host, h, sizeof(host) - 1);
            g_uri_unref(parsed);
        }
    }
    if (host[0] == '\0') {
        strncpy(host, state->host, sizeof(host) - 1);
    }

    WebKitWebContext *context = webkit_web_view_get_context(web_view);
    if (context && certificate) {
        webkit_web_context_allow_tls_certificate_for_host(context, certificate, host);
    }
    webkit_web_view_reload(web_view);
    return TRUE;
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

/* CSS Theme for Sleek Obsidian Glassmorphic UI with Cyberpunk Accents */
static void apply_dark_theme(void) {
    /* Enforce dark theme preferences globally on GTK */
    GtkSettings *gtk_settings = gtk_settings_get_default();
    if (gtk_settings) {
        g_object_set(gtk_settings,
                     "gtk-application-prefer-dark-theme", TRUE,
                     "gtk-theme-name", "Adwaita-dark",
                     NULL);
    }

    GtkCssProvider *provider = gtk_css_provider_new();
    const char *css =
        "* {"
        "  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans CJK SC', 'Noto Sans', sans-serif;"
        "  color: #f1f5f9;"
        "}"
        "window, window.background, window.remote-viber-window {"
        "  background-color: #060911;"
        "  background-image: radial-gradient(circle at 50% 0%, rgba(56, 189, 248, 0.08) 0%, transparent 65%);"
        "  color: #f1f5f9;"
        "}"
        "stack, box, viewport, scrolledwindow {"
        "  background-color: transparent;"
        "}"
        "headerbar, .titlebar, headerbar.titlebar, headerbar.remote-viber-header,"
        "headerbar:backdrop, .titlebar:backdrop {"
        "  background-color: #080c16;"
        "  background-image: linear-gradient(180deg, #0d1424 0%, #080c16 100%);"
        "  border-bottom: 1px solid rgba(255, 255, 255, 0.08);"
        "  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.6);"
        "  padding: 5px 12px;"
        "  min-height: 46px;"
        "  color: #f8fafc;"
        "}"
        "headerbar .title, .titlebar .title {"
        "  font-weight: 700;"
        "  font-size: 13px;"
        "  color: #f8fafc;"
        "  letter-spacing: 0.3px;"
        "}"
        "headerbar .subtitle, .titlebar .subtitle {"
        "  font-size: 11px;"
        "  color: #64748b;"
        "}"
        ".status-pill {"
        "  background-color: rgba(15, 23, 42, 0.85);"
        "  border: 1px solid rgba(56, 189, 248, 0.25);"
        "  border-radius: 9999px;"
        "  padding: 3px 12px;"
        "  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.06);"
        "}"
        "headerbar button, .titlebar button, button.action-btn {"
        "  background-color: rgba(255, 255, 255, 0.05);"
        "  background-image: none;"
        "  border: 1px solid rgba(255, 255, 255, 0.08);"
        "  border-radius: 8px;"
        "  color: #94a3b8;"
        "  font-size: 12px;"
        "  font-weight: 500;"
        "  padding: 4px 11px;"
        "  margin: 0 3px;"
        "  box-shadow: none;"
        "  text-shadow: none;"
        "  transition: all 180ms cubic-bezier(0.16, 1, 0.3, 1);"
        "}"
        "headerbar button:hover, .titlebar button:hover, button.action-btn:hover {"
        "  background-color: rgba(56, 189, 248, 0.12);"
        "  background-image: none;"
        "  border-color: rgba(56, 189, 248, 0.4);"
        "  color: #38bdf8;"
        "  box-shadow: 0 0 14px rgba(56, 189, 248, 0.22);"
        "}"
        "headerbar button:active, .titlebar button:active, button.action-btn:active {"
        "  background-color: rgba(56, 189, 248, 0.25);"
        "  border-color: #38bdf8;"
        "  color: #ffffff;"
        "}"
        "headerbar button.quit-btn:hover, button.action-btn.quit-btn:hover {"
        "  background-color: rgba(239, 68, 68, 0.16);"
        "  background-image: none;"
        "  border-color: rgba(239, 68, 68, 0.45);"
        "  color: #ef4444;"
        "  box-shadow: 0 0 14px rgba(239, 68, 68, 0.25);"
        "}"
        "headerbar button.titlebutton, .titlebar button.titlebutton {"
        "  background-color: transparent;"
        "  background-image: none;"
        "  border: none;"
        "  color: #94a3b8;"
        "  border-radius: 6px;"
        "  padding: 6px;"
        "}"
        "headerbar button.titlebutton:hover, .titlebar button.titlebutton:hover {"
        "  background-color: rgba(255, 255, 255, 0.08);"
        "  color: #ffffff;"
        "}"
        "headerbar button.titlebutton.close:hover, .titlebar button.titlebutton.close:hover {"
        "  background-color: #ef4444;"
        "  color: #ffffff;"
        "}"
        ".glass-card {"
        "  background-color: rgba(13, 20, 36, 0.88);"
        "  background-image: linear-gradient(135deg, rgba(255, 255, 255, 0.04) 0%, rgba(255, 255, 255, 0.01) 100%);"
        "  border: 1px solid rgba(56, 189, 248, 0.22);"
        "  border-radius: 20px;"
        "  padding: 36px 48px;"
        "  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.65), 0 0 40px rgba(56, 189, 248, 0.08);"
        "  min-width: 520px;"
        "}"
        ".tag-badge {"
        "  background-color: rgba(56, 189, 248, 0.12);"
        "  border: 1px solid rgba(56, 189, 248, 0.3);"
        "  border-radius: 9999px;"
        "  padding: 3px 14px;"
        "}"
        ".cmd-box {"
        "  background-color: #030712;"
        "  border: 1px solid rgba(52, 211, 153, 0.35);"
        "  border-radius: 10px;"
        "  padding: 10px 18px;"
        "  box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.6);"
        "}"
        ".retry-btn {"
        "  background-color: #0284c7;"
        "  background-image: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);"
        "  border: 1px solid rgba(56, 189, 248, 0.4);"
        "  border-radius: 10px;"
        "  color: #ffffff;"
        "  font-weight: 600;"
        "  font-size: 13px;"
        "  padding: 8px 22px;"
        "  box-shadow: 0 4px 14px rgba(2, 132, 199, 0.35);"
        "  transition: all 180ms ease-in-out;"
        "}"
        ".retry-btn:hover {"
        "  background-color: #0ea5e9;"
        "  background-image: linear-gradient(135deg, #0ea5e9 0%, #0284c7 100%);"
        "  box-shadow: 0 6px 20px rgba(14, 165, 233, 0.5);"
        "}"
        ".retry-btn:active {"
        "  background-color: #0284c7;"
        "}"
        ".secondary-btn {"
        "  background-color: rgba(30, 41, 59, 0.6);"
        "  background-image: none;"
        "  border: 1px solid rgba(255, 255, 255, 0.12);"
        "  border-radius: 10px;"
        "  color: #cbd5e1;"
        "  font-weight: 500;"
        "  font-size: 13px;"
        "  padding: 8px 18px;"
        "  box-shadow: none;"
        "  text-shadow: none;"
        "  transition: all 180ms ease-in-out;"
        "}"
        ".secondary-btn:hover {"
        "  background-color: rgba(51, 65, 85, 0.85);"
        "  background-image: none;"
        "  color: #38bdf8;"
        "  border-color: rgba(56, 189, 248, 0.35);"
        "  box-shadow: 0 0 12px rgba(56, 189, 248, 0.18);"
        "}"
        ".secondary-btn:active {"
        "  background-color: rgba(30, 41, 59, 0.9);"
        "  background-image: none;"
        "}";

    gtk_css_provider_load_from_data(provider, css, -1, NULL);
    gtk_style_context_add_provider_for_screen(
        gdk_screen_get_default(),
        GTK_STYLE_PROVIDER(provider),
        GTK_STYLE_PROVIDER_PRIORITY_USER
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
            state->is_loading = TRUE;
            webkit_web_view_reload(WEBKIT_WEB_VIEW(state->web_view));
        } else {
            on_load_failed(WEBKIT_WEB_VIEW(state->web_view), WEBKIT_LOAD_FINISHED, state->target_url, NULL, state);
        }
        return TRUE;
    }

    /* 'r' or 'R' when in waiting/loading state: trigger immediate retry */
    if (!state->page_loaded && (event->keyval == GDK_KEY_r || event->keyval == GDK_KEY_R)) {
        on_retry_clicked(NULL, state);
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
        state->is_loading = TRUE;
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
    AppState *state = (AppState *)user_data;
    stop_poll_timer(state);
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
    setenv("WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS", "1", 1);

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

    if (app_state.target_url[0] != '\0') {
        GUri *u = g_uri_parse(app_state.target_url, G_URI_FLAGS_NONE, NULL);
        if (u) {
            const char *h = g_uri_get_host(u);
            int p = g_uri_get_port(u);
            const char *scheme = g_uri_get_scheme(u);
            if (h && h[0] != '\0') {
                strncpy(app_state.host, h, sizeof(app_state.host) - 1);
            }
            if (p > 0) {
                app_state.port = p;
            } else if (scheme && strcmp(scheme, "https") == 0) {
                app_state.port = 443;
            } else if (scheme && strcmp(scheme, "http") == 0) {
                app_state.port = 80;
            }
            g_uri_unref(u);
        }
    } else {
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

    /* Loading / Waiting View: Encapsulated inside a high-end Glassmorphic Floating Panel */
    app_state.loading_box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_widget_set_valign(app_state.loading_box, GTK_ALIGN_CENTER);
    gtk_widget_set_halign(app_state.loading_box, GTK_ALIGN_CENTER);

    GtkWidget *glass_card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 16);
    gtk_style_context_add_class(gtk_widget_get_style_context(glass_card), "glass-card");

    /* Tag badge on top */
    GtkWidget *tag_box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 0);
    gtk_widget_set_halign(tag_box, GTK_ALIGN_CENTER);
    GtkWidget *tag_label = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(tag_label),
        "<span size='smaller' weight='bold' foreground='#38bdf8'>⚡ REMOTE VIBER · NATIVE CLIENT</span>");
    gtk_style_context_add_class(gtk_widget_get_style_context(tag_box), "tag-badge");
    gtk_box_pack_start(GTK_BOX(tag_box), tag_label, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(glass_card), tag_box, FALSE, FALSE, 0);

    /* Main Title */
    app_state.spinner_title = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(app_state.spinner_title),
        "<span size='x-large' weight='bold' foreground='#f8fafc'>RemoteViber 终端拼图工作台</span>");
    gtk_box_pack_start(GTK_BOX(glass_card), app_state.spinner_title, FALSE, FALSE, 0);

    /* Subtitle */
    GtkWidget *sub_lbl = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(sub_lbl),
        "<span size='small' foreground='#94a3b8'>高弹性终端矩阵 · 自动磁吸吸附 · 纯原生零 Chrome 依赖</span>");
    gtk_box_pack_start(GTK_BOX(glass_card), sub_lbl, FALSE, FALSE, 0);

    /* Spinner */
    app_state.spinner = gtk_spinner_new();
    gtk_widget_set_size_request(app_state.spinner, 38, 38);
    gtk_widget_set_halign(app_state.spinner, GTK_ALIGN_CENTER);
    gtk_spinner_start(GTK_SPINNER(app_state.spinner));
    gtk_box_pack_start(GTK_BOX(glass_card), app_state.spinner, FALSE, FALSE, 4);

    /* Wait / Status message */
    app_state.spinner_label = gtk_label_new(NULL);
    gtk_label_set_justify(GTK_LABEL(app_state.spinner_label), GTK_JUSTIFY_CENTER);
    gtk_label_set_line_wrap(GTK_LABEL(app_state.spinner_label), TRUE);
    char initial_wait_text[1024];
    snprintf(initial_wait_text, sizeof(initial_wait_text),
        "<span size='medium' weight='bold' foreground='#38bdf8'>正在等待连接服务端 (http://%s:%d/)...</span>\n\n"
        "<span size='small' foreground='#94a3b8'>客户端与服务端已独立脱离，请在终端独立启动服务端：</span>\n\n"
        "<span font_family='monospace' size='medium' foreground='#34d399'>  ./dist-bin/viber-host-linux-x86_64 --port %d  </span>\n\n"
        "<span size='small' foreground='#64748b'>服务端启动后客户端将毫秒级自动感应并呈现拼图终端</span>",
        app_state.host, app_state.port, app_state.port);
    gtk_label_set_markup(GTK_LABEL(app_state.spinner_label), initial_wait_text);
    gtk_box_pack_start(GTK_BOX(glass_card), app_state.spinner_label, FALSE, FALSE, 4);

    /* Action button row */
    GtkWidget *btn_row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    gtk_widget_set_halign(btn_row, GTK_ALIGN_CENTER);

    app_state.retry_btn = gtk_button_new_with_label("立即重试连接 (R)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.retry_btn), "retry-btn");
    g_signal_connect(app_state.retry_btn, "clicked", G_CALLBACK(on_retry_clicked), &app_state);
    gtk_box_pack_start(GTK_BOX(btn_row), app_state.retry_btn, FALSE, FALSE, 0);

    GtkWidget *btn_browser = gtk_button_new_with_label("打开系统浏览器");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_browser), "secondary-btn");
    g_signal_connect(btn_browser, "clicked", G_CALLBACK(on_open_browser_clicked), &app_state);
    gtk_box_pack_start(GTK_BOX(btn_row), btn_browser, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(glass_card), btn_row, FALSE, FALSE, 6);

    gtk_box_pack_start(GTK_BOX(app_state.loading_box), glass_card, FALSE, FALSE, 0);
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

    /* Set transparent base background so dark obsidian GTK window shows through with zero white flash */
    GdkRGBA trans_bg = { 0.0, 0.0, 0.0, 0.0 };
    webkit_web_view_set_background_color(WEBKIT_WEB_VIEW(app_state.web_view), &trans_bg);

    /* Inject user dark theme CSS to guarantee dark styling across all HTML/body elements and subframes */
    WebKitUserContentManager *ucm = webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(app_state.web_view));
    WebKitUserStyleSheet *sheet = webkit_user_style_sheet_new(
        "html, body { background-color: #060911 !important; color-scheme: dark !important; }"
        "::-webkit-scrollbar { width: 8px; height: 8px; }"
        "::-webkit-scrollbar-track { background: #060911; }"
        "::-webkit-scrollbar-thumb { background: #1e293b; border-radius: 4px; }"
        "::-webkit-scrollbar-thumb:hover { background: #334155; }",
        WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
        WEBKIT_USER_STYLE_LEVEL_USER,
        NULL, NULL
    );
    webkit_user_content_manager_add_style_sheet(ucm, sheet);
    webkit_user_style_sheet_unref(sheet);

    app_state.inspector = webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(app_state.web_view));

    /* Trust self-signed certificates for LAN/remote viber nodes seamlessly */
    WebKitWebsiteDataManager *manager = webkit_web_view_get_website_data_manager(WEBKIT_WEB_VIEW(app_state.web_view));
    if (manager) {
        webkit_website_data_manager_set_tls_errors_policy(manager, WEBKIT_TLS_ERRORS_POLICY_IGNORE);
    }

    /* Connect WebKit events to safely transition and recover from errors */
    g_signal_connect(app_state.web_view, "load-changed", G_CALLBACK(on_load_changed), &app_state);
    g_signal_connect(app_state.web_view, "load-failed", G_CALLBACK(on_load_failed), &app_state);
    g_signal_connect(app_state.web_view, "load-failed-with-tls-errors", G_CALLBACK(on_load_failed_with_tls_errors), &app_state);
    g_signal_connect(app_state.web_view, "web-process-terminated", G_CALLBACK(on_web_process_terminated), &app_state);

    gtk_stack_add_named(GTK_STACK(app_state.stack), app_state.web_view, "webview");
    gtk_container_add(GTK_CONTAINER(app_state.window), app_state.stack);

    /* Explicitly show loading view first */
    gtk_stack_set_visible_child_name(GTK_STACK(app_state.stack), "loading");

    /* Connect window signals */
    g_signal_connect(app_state.window, "key-press-event", G_CALLBACK(on_key_press), &app_state);
    g_signal_connect(app_state.window, "destroy", G_CALLBACK(on_window_destroy), &app_state);

    gtk_widget_show_all(app_state.window);

    /* Initial check: if server is ready, connect immediately; otherwise poll every 1 second */
    if (check_host_ready_cb(&app_state) == G_SOURCE_CONTINUE) {
        start_poll_timer(&app_state, 1000);
    }

    gtk_main();

    return 0;
}
