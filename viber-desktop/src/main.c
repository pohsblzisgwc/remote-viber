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
    GtkWidget *start_host_btn;
    GtkWidget *retry_btn;
    GtkWidget *open_browser_btn;
    GtkWidget *web_view;
    WebKitWebInspector *inspector;

    /* Native desktop action buttons in header */
    GtkWidget *btn_quick_term;
    GtkWidget *btn_launch_agent;
    GtkWidget *btn_mosaic;
    GtkWidget *btn_single;
    GtkWidget *btn_dashboard;
    GtkWidget *btn_pairing;
    GtkWidget *btn_reload;
    GtkWidget *btn_fullscreen;
    GtkWidget *btn_quit;
    
    char host[128];
    int port;
    char target_url[512];
    char app_dir[1024];
    char local_pairing_code[2048];
    
    double zoom_level;
    gboolean is_fullscreen;
    gboolean page_loaded;
    gboolean is_loading;
    gboolean load_failed;
    gboolean explicit_url;
    gboolean has_fallback_tried;
    guint poll_timer_id;
    pid_t spawned_host_pid;
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

/* Probe whether the target port is serving plain HTTP or HTTPS */
static gboolean probe_is_plain_http(const char *host, int port) {
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) return FALSE;

    int sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0) {
        freeaddrinfo(res);
        return FALSE;
    }

    struct timeval tv = { .tv_sec = 0, .tv_usec = 300000 }; /* 300ms timeout */
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    if (connect(sock, res->ai_addr, res->ai_addrlen) != 0) {
        freeaddrinfo(res);
        close(sock);
        return FALSE;
    }
    freeaddrinfo(res);

    const char *probe_req = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    send(sock, probe_req, strlen(probe_req), 0);

    char buf[16] = {0};
    ssize_t n = recv(sock, buf, 4, 0);
    close(sock);

    if (n >= 4 && strncmp(buf, "HTTP", 4) == 0) {
        return TRUE; /* Server responded with HTTP status line */
    }
    return FALSE; /* Closed, TLS handshake expected, or error */
}

/* Dispatch JavaScript into WebKit view */
static void dispatch_web_js(WebKitWebView *web_view, const char *js) {
    if (!web_view || !js) return;
#if WEBKIT_CHECK_VERSION(2, 40, 0)
    webkit_web_view_evaluate_javascript(web_view, js, -1, NULL, NULL, NULL, NULL, NULL);
#else
    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    webkit_web_view_run_javascript(web_view, js, NULL, NULL, NULL);
    G_GNUC_END_IGNORE_DEPRECATIONS
#endif
}

/* Read local host pairing code if available */
static void find_local_pairing_code(AppState *state) {
    if (state->local_pairing_code[0] != '\0') return;
    if (strcmp(state->host, "127.0.0.1") != 0 && strcmp(state->host, "localhost") != 0) return;

    char *exe = g_strdup_printf("%s/viber-host-linux-x86_64", state->app_dir);
    if (access(exe, X_OK) != 0) {
        g_free(exe);
        exe = g_strdup_printf("%s/../dist-bin/viber-host-linux-x86_64", state->app_dir);
    }
    if (access(exe, X_OK) != 0) {
        g_free(exe);
        exe = g_strdup("./dist-bin/viber-host-linux-x86_64");
    }
    if (access(exe, X_OK) != 0) {
        g_free(exe);
        return;
    }

    char *cmd = g_strdup_printf("'%s' --pair-info 2>/dev/null", exe);
    g_free(exe);
    FILE *fp = popen(cmd, "r");
    g_free(cmd);
    if (!fp) return;

    char line[4096];
    while (fgets(line, sizeof(line), fp)) {
        char *trimmed = g_strstrip(line);
        if (g_str_has_prefix(trimmed, "Local UI:")) {
            char *url_part = g_strstrip(trimmed + 9);
            if (!state->explicit_url && url_part[0] != '\0') {
                strncpy(state->target_url, url_part, sizeof(state->target_url) - 1);
            }
        }
        if (g_str_has_prefix(trimmed, "eyJ2")) {
            strncpy(state->local_pairing_code, trimmed, sizeof(state->local_pairing_code) - 1);
            break;
        }
    }
    pclose(fp);
    if (state->local_pairing_code[0] != '\0') {
        g_print("[RemoteViber] 成功检测到本机配对凭据，将自动完成本地工作台安全认证。\n");
        fflush(stdout);
    }
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
        return G_SOURCE_CONTINUE;
    }

    static gboolean s_notified_waiting = FALSE;

    if (is_port_open(state->host, state->port)) {
        s_notified_waiting = FALSE;

        find_local_pairing_code(state);

        if (!state->explicit_url) {
            if (probe_is_plain_http(state->host, state->port)) {
                snprintf(state->target_url, sizeof(state->target_url), "http://%s:%d/", state->host, state->port);
            } else {
                snprintf(state->target_url, sizeof(state->target_url), "https://%s:%d/", state->host, state->port);
            }
        }

        g_print("[RemoteViber] 服务端已连接 (%s)，正在载入工作台...\n", state->target_url);
        fflush(stdout);

        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='medium' weight='bold' foreground='#38bdf8'>已连接服务端！</span>\n\n"
            "<span size='small' foreground='#94a3b8'>正在解析并呈现终端拼图网格工作台...</span>");
        
        char status_str[128];
        snprintf(status_str, sizeof(status_str),
            "<span color='#10b981' font_weight='bold'>●</span> <span color='#38bdf8' font_size='small'>%d 准备中</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->status_label), status_str);

        state->is_loading = TRUE;
        state->load_failed = FALSE;
        state->poll_timer_id = 0;
        webkit_web_view_load_uri(WEBKIT_WEB_VIEW(state->web_view), state->target_url);
        return G_SOURCE_REMOVE;
    }

    /* Server not reachable yet: update waiting screen and notify in terminal */
    if (!s_notified_waiting) {
        s_notified_waiting = TRUE;
        g_print("[RemoteViber] 正在等待服务端上线 (%s)...\n", state->target_url);
        g_print("[RemoteViber] 提示: 可点击 GUI 界面【一键启动本地服务端】，或在另一终端窗口启动：\n");
        g_print("      ./dist-bin/viber-host-linux-x86_64 --port %d\n", state->port);
        fflush(stdout);
    }
    char wait_msg[1024];
    snprintf(wait_msg, sizeof(wait_msg),
        "<span size='medium' weight='bold' foreground='#38bdf8'>正在等待连接服务端 (%s)...</span>\n\n"
        "<span size='small' foreground='#94a3b8'>服务端尚未启动。点击下方绿色按钮可直接在 GUI 启动：</span>\n\n"
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
    state->load_failed = FALSE;
    stop_poll_timer(state);
    if (check_host_ready_cb(state) == G_SOURCE_CONTINUE) {
        start_poll_timer(state, 1000);
    }
}

/* Launch local host server directly from GUI */
static void on_start_host_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    if (is_port_open(state->host, state->port)) {
        g_print("[RemoteViber] 服务端已在运行 (端口 %d)\n", state->port);
        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='medium' weight='bold' foreground='#38bdf8'>服务端已在运行！</span>\n\n"
            "<span size='small' foreground='#94a3b8'>正在载入工作台...</span>");
        on_retry_clicked(NULL, state);
        return;
    }

    g_print("[RemoteViber] 正在通过 GUI 一键拉起本地服务端 (端口 %d)...\n", state->port);
    fflush(stdout);

    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        char port_str[16];
        snprintf(port_str, sizeof(port_str), "%d", state->port);
        char *exe = g_strdup_printf("%s/viber-host-linux-x86_64", state->app_dir);
        if (access(exe, X_OK) != 0) {
            g_free(exe);
            exe = g_strdup_printf("%s/../dist-bin/viber-host-linux-x86_64", state->app_dir);
        }
        if (access(exe, X_OK) != 0) {
            g_free(exe);
            exe = g_strdup("./dist-bin/viber-host-linux-x86_64");
        }
        execl(exe, "viber-host-linux-x86_64", "--port", port_str, NULL);
        g_free(exe);
        _exit(127);
    } else if (pid > 0) {
        state->spawned_host_pid = pid;
        g_print("[RemoteViber] 本地服务端进程已拉起 (PID: %d)，等待端口就绪...\n", pid);
        fflush(stdout);
        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='medium' weight='bold' foreground='#34d399'>本地服务端进程已拉起！</span>\n\n"
            "<span size='small' foreground='#94a3b8'>正在等待端口就绪并自动载入工作台...</span>");
        start_poll_timer(state, 500);
    }
}

/* Open in system browser button handler */
static void on_open_browser_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    gtk_show_uri_on_window(GTK_WINDOW(state->window), state->target_url, GDK_CURRENT_TIME, NULL);
}

/* Protocol toggle button handler (HTTP <-> HTTPS) */
static void on_toggle_protocol_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    state->explicit_url = TRUE;
    state->has_fallback_tried = TRUE;
    if (g_str_has_prefix(state->target_url, "https://")) {
        snprintf(state->target_url, sizeof(state->target_url), "http://%s:%d/", state->host, state->port);
    } else {
        snprintf(state->target_url, sizeof(state->target_url), "https://%s:%d/", state->host, state->port);
    }
    g_print("[RemoteViber] 手动切换连接协议为: %s\n", state->target_url);
    fflush(stdout);
    on_retry_clicked(NULL, state);
}

/* WebKit load-changed handler */
static void on_load_changed(WebKitWebView *web_view, WebKitLoadEvent event, gpointer user_data) {
    AppState *state = (AppState *)user_data;

    if (event == WEBKIT_LOAD_STARTED) {
        state->is_loading = TRUE;
        state->load_failed = FALSE;
    } else if (event == WEBKIT_LOAD_COMMITTED) {
        state->load_failed = FALSE;
        gtk_label_set_markup(GTK_LABEL(state->spinner_label),
            "<span size='medium' weight='bold' foreground='#38bdf8'>已建立通信连接</span>\n\n"
            "<span size='small' foreground='#94a3b8'>正在解析渲染拼图工作台视图...</span>");
        
        /* Auto-inject local pairing code if available */
        if (state->local_pairing_code[0] != '\0') {
            char *js = g_strdup_printf(
                "try {"
                "  sessionStorage.setItem('viber_local_pairing_code', '%s');"
                "  if (window.viber_import_pairing) { window.viber_import_pairing('%s'); }"
                "} catch(e) {}",
                state->local_pairing_code, state->local_pairing_code);
            dispatch_web_js(web_view, js);
            g_free(js);
        }
    } else if (event == WEBKIT_LOAD_FINISHED) {
        state->is_loading = FALSE;
        if (state->load_failed) {
            /* Aborted or failed load; keep loading view visible, do not show blank screen! */
            return;
        }

        state->page_loaded = TRUE;
        state->has_fallback_tried = FALSE;
        stop_poll_timer(state);
        gtk_spinner_stop(GTK_SPINNER(state->spinner));
        gtk_stack_set_visible_child(GTK_STACK(state->stack), state->web_view);
        
        char status_str[128];
        snprintf(status_str, sizeof(status_str),
            "<span color='#10b981' font_weight='bold'>●</span> <span color='#38bdf8' font_size='small'>%d 在线</span>", state->port);
        gtk_label_set_markup(GTK_LABEL(state->status_label), status_str);

        if (state->local_pairing_code[0] != '\0') {
            char *js = g_strdup_printf(
                "try {"
                "  sessionStorage.setItem('viber_local_pairing_code', '%s');"
                "  if (window.viber_import_pairing) { window.viber_import_pairing('%s'); }"
                "} catch(e) {}",
                state->local_pairing_code, state->local_pairing_code);
            dispatch_web_js(web_view, js);
            g_free(js);
        }
    }
}

/* WebKit load-failed handler */
static gboolean on_load_failed(WebKitWebView *web_view, WebKitLoadEvent event, gchar *failing_uri, GError *error, gpointer user_data) {
    (void)web_view; (void)event;
    AppState *state = (AppState *)user_data;

    /* 1. Ignore benign cancellation errors (e.g. subresource abort, reload, redirect) */
    if (error) {
        if (g_error_matches(error, WEBKIT_NETWORK_ERROR, WEBKIT_NETWORK_ERROR_CANCELLED) ||
            g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE) ||
            g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_CANNOT_SHOW_MIME_TYPE) ||
            (error->message && (strstr(error->message, "cancelled") != NULL ||
                                strstr(error->message, "canceled") != NULL))) {
            return TRUE;
        }
    }

    /* 2. Subresource check: if failing_uri is not the main page URL, completely ignore it */
    if (failing_uri && state->target_url[0] != '\0') {
        char norm_target[512] = {0};
        char norm_failing[512] = {0};
        g_strlcpy(norm_target, state->target_url, sizeof(norm_target));
        g_strlcpy(norm_failing, failing_uri, sizeof(norm_failing));
        size_t lt = strlen(norm_target);
        if (lt > 0 && norm_target[lt - 1] == '/') norm_target[lt - 1] = '\0';
        size_t lf = strlen(norm_failing);
        if (lf > 0 && norm_failing[lf - 1] == '/') norm_failing[lf - 1] = '\0';

        if (strcmp(norm_target, norm_failing) != 0) {
            /* Any failure from a subresource (/assets/..., favicon, cdn fonts, etc.) MUST NOT break or flip the page! */
            return TRUE;
        }
    }

    /* 3. If already loaded, ignore any subresource failures */
    if (state->page_loaded) {
        return TRUE;
    }

    /* 4. If HTTPS failed due to TLS handshake termination, probe if server is actually plain HTTP */
    if (!state->explicit_url && !state->has_fallback_tried && g_str_has_prefix(state->target_url, "https://")) {
        if (error && error->message &&
            (strstr(error->message, "non-properly terminated") != NULL ||
             strstr(error->message, "unexpected TLS packet") != NULL ||
             strstr(error->message, "wrong version number") != NULL)) {
            if (probe_is_plain_http(state->host, state->port)) {
                state->has_fallback_tried = TRUE;
                snprintf(state->target_url, sizeof(state->target_url), "http://%s:%d/", state->host, state->port);
                g_print("[RemoteViber] 探测到服务端运行在 HTTP 模式，已自动切换至: %s\n", state->target_url);
                fflush(stdout);
                state->is_loading = TRUE;
                state->load_failed = FALSE;
                webkit_web_view_load_uri(WEBKIT_WEB_VIEW(state->web_view), state->target_url);
                return TRUE;
            }
        }
    }

    /* 5. If HTTP failed, probe if server is actually HTTPS */
    if (!state->explicit_url && !state->has_fallback_tried && g_str_has_prefix(state->target_url, "http://")) {
        if (!probe_is_plain_http(state->host, state->port)) {
            state->has_fallback_tried = TRUE;
            snprintf(state->target_url, sizeof(state->target_url), "https://%s:%d/", state->host, state->port);
            g_print("[RemoteViber] 探测到服务端运行在 HTTPS 模式，已自动切换至: %s\n", state->target_url);
            fflush(stdout);
            state->is_loading = TRUE;
            state->load_failed = FALSE;
            webkit_web_view_load_uri(WEBKIT_WEB_VIEW(state->web_view), state->target_url);
            return TRUE;
        }
    }

    g_printerr("[RemoteViber] 页面加载失败: %s (原因: %s)\n", failing_uri ? failing_uri : "", error ? error->message : "无法连接");

    state->load_failed = TRUE;
    state->page_loaded = FALSE;
    state->is_loading = FALSE;
    gtk_stack_set_visible_child(GTK_STACK(state->stack), state->loading_box);
    gtk_spinner_start(GTK_SPINNER(state->spinner));

    char err_msg[1024];
    snprintf(err_msg, sizeof(err_msg),
        "<span size='medium' weight='bold' foreground='#ef4444'>未能载入工作台页面 (%s)</span>\n\n"
        "<span size='small' foreground='#94a3b8'>请确认服务端正在运行，或点击【一键启动本地服务端】：</span>\n"
        "<span font_family='monospace' size='small' foreground='#34d399'>  ./dist-bin/viber-host-linux-x86_64 --port %d  </span>\n\n"
        "<span size='small' foreground='#64748b'>正在自动检测重新连接...</span>",
        state->target_url, state->port);
    gtk_label_set_markup(GTK_LABEL(state->spinner_label), err_msg);

    char badge_str[128];
    snprintf(badge_str, sizeof(badge_str),
        "<span color='#ef4444' font_weight='bold'>✕</span> <span color='#94a3b8' font_size='small'>已断开 (%d)</span>", state->port);
    gtk_label_set_markup(GTK_LABEL(state->status_label), badge_str);

    start_poll_timer(state, 1000);
    return TRUE;
}

/* WebKit load-failed-with-tls-errors handler: allow self-signed TLS certificates for LAN/remote viber host */
static gboolean on_load_failed_with_tls_errors(WebKitWebView *web_view, gchar *failing_uri,
                                               GTlsCertificate *certificate, GTlsCertificateFlags errors,
                                               gpointer user_data) {
    (void)errors;
    AppState *state = (AppState *)user_data;
    g_print("[RemoteViber] 自动信任服务端自签名 TLS 证书 (%s)\n", failing_uri ? failing_uri : "unknown");
    fflush(stdout);

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
    return TRUE;
}

/* WebKit WebProcess crash handler */
static void on_web_process_terminated(WebKitWebView *web_view, WebKitWebProcessTerminationReason reason, gpointer user_data) {
    AppState *state = (AppState *)user_data;
    g_printerr("[RemoteViber] 网页渲染进程退出 (代码: %d)，正在自动重连...\n", reason);

    state->load_failed = TRUE;
    state->page_loaded = FALSE;
    state->is_loading = FALSE;
    gtk_stack_set_visible_child(GTK_STACK(state->stack), state->loading_box);
    gtk_label_set_markup(GTK_LABEL(state->spinner_label),
        "<span size='small' foreground='#f59e0b'>网页渲染进程正在自动恢复，请稍候...</span>");
    gtk_spinner_start(GTK_SPINNER(state->spinner));

    webkit_web_view_reload(web_view);
}

/* Apply GTK custom styles */
static void apply_dark_theme(void) {
    GtkSettings *gtk_settings = gtk_settings_get_default();
    if (gtk_settings) {
        g_object_set(gtk_settings,
            "gtk-xft-antialias", 1,
            "gtk-xft-hinting", 1,
            "gtk-xft-hintstyle", "hintslight",
            "gtk-xft-rgba", "rgb",
            NULL);
    }

    GtkCssProvider *provider = gtk_css_provider_new();
    const char *css =
        "window, .remote-viber-window {"
        "  background-color: #060911;"
        "  color: #f8fafc;"
        "  font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Noto Sans\", Ubuntu, Cantarell, sans-serif;"
        "}"
        "headerbar, .remote-viber-header {"
        "  background-color: #0b0f19;"
        "  background-image: linear-gradient(to bottom, #0d1424, #080c16);"
        "  border-bottom: 1px solid rgba(56, 189, 248, 0.25);"
        "  padding: 4px 8px;"
        "  min-height: 44px;"
        "  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);"
        "}"
        "headerbar .title, .titlebar .title {"
        "  color: #f8fafc;"
        "  font-weight: 700;"
        "  font-size: 13px;"
        "  letter-spacing: 0.5px;"
        "}"
        "headerbar .subtitle, .titlebar .subtitle {"
        "  color: #94a3b8;"
        "  font-size: 11px;"
        "}"
        ".status-pill {"
        "  background-color: rgba(15, 23, 42, 0.85);"
        "  border: 1px solid rgba(56, 189, 248, 0.25);"
        "  border-radius: 9999px;"
        "  padding: 2px 10px;"
        "}"
        "headerbar button, .titlebar button, button.action-btn {"
        "  background-color: rgba(30, 41, 59, 0.75);"
        "  background-image: none;"
        "  border: 1px solid rgba(56, 189, 248, 0.3);"
        "  color: #e2e8f0;"
        "  border-radius: 8px;"
        "  padding: 4px 12px;"
        "  font-size: 12px;"
        "  font-weight: 600;"
        "  font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Noto Sans\", Ubuntu, Cantarell, sans-serif;"
        "  box-shadow: none;"
        "  text-shadow: none;"
        "  transition: all 150ms ease-in-out;"
        "}"
        "headerbar button:hover, .titlebar button:hover, button.action-btn:hover {"
        "  background-color: rgba(56, 189, 248, 0.18);"
        "  border-color: rgba(56, 189, 248, 0.6);"
        "  color: #38bdf8;"
        "  box-shadow: 0 0 12px rgba(56, 189, 248, 0.25);"
        "}"
        "headerbar button:active, .titlebar button:active, button.action-btn:active {"
        "  background-color: rgba(56, 189, 248, 0.35);"
        "  border-color: #38bdf8;"
        "  color: #ffffff;"
        "}"
        "button.action-btn.quick-term-btn {"
        "  background-color: #0284c7;"
        "  background-image: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);"
        "  border-color: rgba(56, 189, 248, 0.6);"
        "  color: #ffffff;"
        "  font-weight: 600;"
        "  box-shadow: 0 2px 8px rgba(2, 132, 199, 0.35);"
        "}"
        "button.action-btn.quick-term-btn:hover {"
        "  background-color: #0ea5e9;"
        "  border-color: #38bdf8;"
        "  box-shadow: 0 0 16px rgba(56, 189, 248, 0.5);"
        "}"
        "headerbar button.quit-btn:hover, button.action-btn.quit-btn:hover {"
        "  background-color: rgba(239, 68, 68, 0.2);"
        "  border-color: rgba(239, 68, 68, 0.5);"
        "  color: #ef4444;"
        "  box-shadow: 0 0 12px rgba(239, 68, 68, 0.3);"
        "}"
        ".glass-card {"
        "  background-color: rgba(13, 20, 36, 0.92);"
        "  background-image: linear-gradient(135deg, rgba(255, 255, 255, 0.05) 0%, rgba(255, 255, 255, 0.01) 100%);"
        "  border: 1px solid rgba(56, 189, 248, 0.3);"
        "  border-radius: 20px;"
        "  padding: 36px 48px;"
        "  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.7), 0 0 40px rgba(56, 189, 248, 0.1);"
        "  min-width: 540px;"
        "}"
        ".tag-badge {"
        "  background-color: rgba(56, 189, 248, 0.12);"
        "  border: 1px solid rgba(56, 189, 248, 0.3);"
        "  border-radius: 9999px;"
        "  padding: 3px 14px;"
        "}"
        ".start-host-btn {"
        "  background-color: #059669;"
        "  background-image: linear-gradient(135deg, #10b981 0%, #059669 100%);"
        "  border: 1px solid rgba(52, 211, 153, 0.45);"
        "  border-radius: 10px;"
        "  color: #ffffff;"
        "  font-weight: 700;"
        "  font-size: 13px;"
        "  padding: 9px 22px;"
        "  box-shadow: 0 4px 14px rgba(16, 185, 129, 0.35);"
        "  transition: all 180ms ease-in-out;"
        "}"
        ".start-host-btn:hover {"
        "  background-color: #10b981;"
        "  background-image: linear-gradient(135deg, #34d399 0%, #10b981 100%);"
        "  box-shadow: 0 6px 20px rgba(52, 211, 153, 0.55);"
        "}"
        ".retry-btn {"
        "  background-color: #0284c7;"
        "  background-image: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);"
        "  border: 1px solid rgba(56, 189, 248, 0.4);"
        "  border-radius: 10px;"
        "  color: #ffffff;"
        "  font-weight: 600;"
        "  font-size: 13px;"
        "  padding: 9px 20px;"
        "  box-shadow: 0 4px 14px rgba(2, 132, 199, 0.35);"
        "  transition: all 180ms ease-in-out;"
        "}"
        ".retry-btn:hover {"
        "  background-color: #0ea5e9;"
        "  background-image: linear-gradient(135deg, #0ea5e9 0%, #0284c7 100%);"
        "  box-shadow: 0 6px 20px rgba(14, 165, 233, 0.5);"
        "}"
        ".secondary-btn {"
        "  background-color: rgba(30, 41, 59, 0.7);"
        "  background-image: none;"
        "  border: 1px solid rgba(255, 255, 255, 0.15);"
        "  border-radius: 10px;"
        "  color: #cbd5e1;"
        "  font-weight: 500;"
        "  font-size: 13px;"
        "  padding: 9px 18px;"
        "  transition: all 180ms ease-in-out;"
        "}"
        ".secondary-btn:hover {"
        "  background-color: rgba(51, 65, 85, 0.9);"
        "  color: #38bdf8;"
        "  border-color: rgba(56, 189, 248, 0.35);"
        "  box-shadow: 0 0 12px rgba(56, 189, 248, 0.2);"
        "}";

    gtk_css_provider_load_from_data(provider, css, -1, NULL);
    gtk_style_context_add_provider_for_screen(
        gdk_screen_get_default(),
        GTK_STYLE_PROVIDER(provider),
        GTK_STYLE_PROVIDER_PRIORITY_USER
    );
    g_object_unref(provider);
}

/* Native HeaderBar Actions */
static void on_quick_term_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js =
        "try {"
        "  window.dispatchEvent(new CustomEvent('viber:quickTerminal'));"
        "  if (window.viber && window.viber.quickTerminal) { window.viber.quickTerminal(); }"
        "  else {"
        "    var btns = Array.from(document.querySelectorAll('button'));"
        "    var target = btns.find(function(b) { return (b.innerText && b.innerText.indexOf('新建终端') !== -1) || (b.title && b.title.indexOf('开启纯交互终端') !== -1); });"
        "    if (target) target.click();"
        "  }"
        "} catch(e) { console.error('quickTerminal error:', e); }";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_launch_agent_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js =
        "try {"
        "  window.dispatchEvent(new CustomEvent('viber:openLaunch'));"
        "  if (window.viber && window.viber.openLaunch) { window.viber.openLaunch(); }"
        "  else {"
        "    var btns = Array.from(document.querySelectorAll('button'));"
        "    var target = btns.find(function(b) { return (b.innerText && b.innerText.indexOf('启动 Agent') !== -1) || (b.title && b.title.indexOf('启动 Agent') !== -1); });"
        "    if (target) target.click();"
        "  }"
        "} catch(e) { console.error('launchAgent error:', e); }";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_mosaic_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js =
        "window.dispatchEvent(new KeyboardEvent('keydown', { key: 'm', altKey: true, bubbles: true }));"
        "(function() {"
        "  if (window.viber && window.viber.switchView) { window.viber.switchView('mosaic'); return; }"
        "  var btns = Array.from(document.querySelectorAll('button'));"
        "  var target = btns.find(function(b) { return b.innerText && b.innerText.indexOf('平铺视图') !== -1; });"
        "  if (target) target.click();"
        "})();";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_single_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js =
        "(function() {"
        "  if (window.viber && window.viber.switchView) { window.viber.switchView('terminal'); return; }"
        "  var btns = Array.from(document.querySelectorAll('button'));"
        "  var target = btns.find(function(b) { return (b.innerText && b.innerText.indexOf('终端工作台') !== -1) || (b.innerText && b.innerText.indexOf('终端') !== -1); });"
        "  if (target) target.click();"
        "})();";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_dashboard_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js =
        "(function() {"
        "  if (window.viber && window.viber.switchView) { window.viber.switchView('dashboard'); return; }"
        "  var btns = Array.from(document.querySelectorAll('button'));"
        "  var target = btns.find(function(b) { return (b.innerText && b.innerText.indexOf('控制面板') !== -1) || (b.innerText && b.innerText.indexOf('看板') !== -1); });"
        "  if (target) target.click();"
        "})();";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_pairing_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    const char *js =
        "(function() {"
        "  if (window.viber && window.viber.openPairing) { window.viber.openPairing(); return; }"
        "  var btns = Array.from(document.querySelectorAll('button'));"
        "  var target = btns.find(function(b) { return b.title && b.title.indexOf('网络配对') !== -1; });"
        "  if (target) target.click();"
        "})();";
    dispatch_web_js(WEBKIT_WEB_VIEW(state->web_view), js);
}

static void on_reload_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    if (is_port_open(state->host, state->port)) {
        state->is_loading = TRUE;
        state->load_failed = FALSE;
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

static void on_quit_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn;
    AppState *state = (AppState *)user_data;
    gtk_window_close(GTK_WINDOW(state->window));
}

/* Mouse wheel zoom handler (Ctrl+Scroll) */
static gboolean on_scroll_event(GtkWidget *widget, GdkEventScroll *event, gpointer user_data) {
    (void)widget;
    AppState *state = (AppState *)user_data;
    if (event->state & GDK_CONTROL_MASK) {
        if (event->direction == GDK_SCROLL_UP || (event->direction == GDK_SCROLL_SMOOTH && event->delta_y < 0)) {
            state->zoom_level += 0.05;
            if (state->zoom_level > 3.0) state->zoom_level = 3.0;
            webkit_web_view_set_zoom_level(WEBKIT_WEB_VIEW(state->web_view), state->zoom_level);
            return TRUE;
        } else if (event->direction == GDK_SCROLL_DOWN || (event->direction == GDK_SCROLL_SMOOTH && event->delta_y > 0)) {
            state->zoom_level -= 0.05;
            if (state->zoom_level < 0.5) state->zoom_level = 0.5;
            webkit_web_view_set_zoom_level(WEBKIT_WEB_VIEW(state->web_view), state->zoom_level);
            return TRUE;
        }
    }
    return FALSE;
}

/* Keyboard shortcuts handler */
static gboolean on_key_press(GtkWidget *widget, GdkEventKey *event, gpointer user_data) {
    (void)widget;
    AppState *state = (AppState *)user_data;

    /* Ctrl+Q: Close client window */
    if ((event->state & GDK_CONTROL_MASK) && (event->keyval == GDK_KEY_q || event->keyval == GDK_KEY_Q)) {
        gtk_window_close(GTK_WINDOW(state->window));
        return TRUE;
    }

    /* F11: Fullscreen toggle */
    if (event->keyval == GDK_KEY_F11) {
        on_fullscreen_clicked(NULL, state);
        return TRUE;
    }

    /* Alt+N: New Terminal */
    if ((event->state & GDK_MOD1_MASK) && (event->keyval == GDK_KEY_n || event->keyval == GDK_KEY_N)) {
        on_quick_term_clicked(NULL, state);
        return TRUE;
    }

    /* Alt+A: Launch Agent */
    if ((event->state & GDK_MOD1_MASK) && (event->keyval == GDK_KEY_a || event->keyval == GDK_KEY_A)) {
        on_launch_agent_clicked(NULL, state);
        return TRUE;
    }

    /* Zoom Controls: Ctrl+Plus, Ctrl+Minus, Ctrl+0 */
    if (event->state & GDK_CONTROL_MASK) {
        if (event->keyval == GDK_KEY_plus || event->keyval == GDK_KEY_equal || event->keyval == GDK_KEY_KP_Add) {
            state->zoom_level += 0.1;
            if (state->zoom_level > 3.0) state->zoom_level = 3.0;
            webkit_web_view_set_zoom_level(WEBKIT_WEB_VIEW(state->web_view), state->zoom_level);
            g_print("[RemoteViber] 工作台缩放: %.0f%%\n", state->zoom_level * 100);
            return TRUE;
        }
        if (event->keyval == GDK_KEY_minus || event->keyval == GDK_KEY_underscore || event->keyval == GDK_KEY_KP_Subtract) {
            state->zoom_level -= 0.1;
            if (state->zoom_level < 0.5) state->zoom_level = 0.5;
            webkit_web_view_set_zoom_level(WEBKIT_WEB_VIEW(state->web_view), state->zoom_level);
            g_print("[RemoteViber] 工作台缩放: %.0f%%\n", state->zoom_level * 100);
            return TRUE;
        }
        if (event->keyval == GDK_KEY_0 || event->keyval == GDK_KEY_KP_0) {
            state->zoom_level = 1.0;
            webkit_web_view_set_zoom_level(WEBKIT_WEB_VIEW(state->web_view), state->zoom_level);
            g_print("[RemoteViber] 工作台缩放已重置: 100%%\n");
            return TRUE;
        }
    }

    /* Alt+M: Mosaic Matrix View */
    if ((event->state & GDK_MOD1_MASK) && (event->keyval == GDK_KEY_m || event->keyval == GDK_KEY_M)) {
        on_mosaic_clicked(NULL, state);
        return TRUE;
    }

    /* Alt+\: Single Terminal View */
    if ((event->state & GDK_MOD1_MASK) && event->keyval == GDK_KEY_backslash) {
        on_single_clicked(NULL, state);
        return TRUE;
    }

    /* Alt+D: Dashboard View */
    if ((event->state & GDK_MOD1_MASK) && (event->keyval == GDK_KEY_d || event->keyval == GDK_KEY_D)) {
        on_dashboard_clicked(NULL, state);
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
        on_reload_clicked(NULL, state);
        return TRUE;
    }

    /* 'r' or 'R' when waiting: retry */
    if (!state->page_loaded && (event->keyval == GDK_KEY_r || event->keyval == GDK_KEY_R)) {
        on_retry_clicked(NULL, state);
        return TRUE;
    }

    return FALSE;
}

/* Clean exit handler */
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

    gboolean detach_to_background = FALSE;
    gboolean force_software_rendering = FALSE;
    gboolean launch_browser_mode = FALSE;

    /* Parse command line arguments */
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--port") == 0 && i + 1 < argc) {
            app_state.port = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--host") == 0 && i + 1 < argc) {
            strncpy(app_state.host, argv[++i], sizeof(app_state.host) - 1);
        } else if (strcmp(argv[i], "--url") == 0 && i + 1 < argc) {
            snprintf(app_state.target_url, sizeof(app_state.target_url), "%s", argv[++i]);
            app_state.explicit_url = TRUE;
        } else if (strcmp(argv[i], "--detach") == 0 || strcmp(argv[i], "-d") == 0 || strcmp(argv[i], "--bg") == 0) {
            detach_to_background = TRUE;
        } else if (strcmp(argv[i], "--software") == 0 || strcmp(argv[i], "--software-rendering") == 0 || strcmp(argv[i], "--no-accel") == 0) {
            force_software_rendering = TRUE;
        } else if (strcmp(argv[i], "--browser") == 0 || strcmp(argv[i], "--chrome") == 0 || strcmp(argv[i], "--app") == 0) {
            launch_browser_mode = TRUE;
        } else if (strcmp(argv[i], "--zoom") == 0 && i + 1 < argc) {
            double z = atof(argv[++i]);
            if (z >= 0.5 && z <= 3.0) {
                app_state.zoom_level = z;
            }
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            g_print("RemoteViber 原生桌面工作台 (Linux Native Desktop Client)\n"
                    "基于 GTK+ 3.0 & WebKit2GTK 4.1 原生打造 (支持纯客户端模式与一键服务端控制)\n\n"
                    "用法: viber-desktop-linux [选项]\n\n"
                    "选项:\n"
                    "  --port <port>   指定服务端连接端口 (默认: 8765)\n"
                    "  --host <ip>     指定服务端连接IP (默认: 127.0.0.1)\n"
                    "  --url <url>     直接指定连接完整 URL (例如 https://192.168.1.100:8765/)\n"
                    "  --zoom <factor> 工作台缩放比例 (默认: 1.0, 范围: 0.5 - 3.0)\n"
                    "  --software      强制软件渲染兼容模式 (禁用 GPU 硬件加速)\n"
                    "  --browser       在系统 Chrome/Chromium 独立应用窗口中启动工作台\n"
                    "  -d, --detach    转入后台独立运行，立即释放终端交互提示符\n"
                    "  --help, -h      显示帮助信息\n\n"
                    "快捷键:\n"
                    "  Alt+N           ➕ 快速创建并启动终端\n"
                    "  Alt+A           🚀 启动 Agent 智能体\n"
                    "  Alt+M           🔲 切换至 4格拼图终端矩阵\n"
                    "  Alt+\\           🖥️ 切换至单屏聚焦终端\n"
                    "  Alt+D           📊 切换至 Agent 仪表盘\n"
                    "  Ctrl++ / Ctrl+- 🔍 放大 / 缩小工作台 (支持 Ctrl+滚轮)\n"
                    "  Ctrl+0          🔍 重置工作台缩放到 100%%\n"
                    "  F11             🖥️ 全屏模式切换\n"
                    "  Ctrl+R / F5     🔄 刷新工作台\n"
                    "  Ctrl+Q          ❌ 关闭桌面客户端 (不影响服务端后台运行)\n");
            return 0;
        }
    }

    /* Enforce modern Linux compositor & driver compatibility */
    setenv("WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS", "1", 1);
    /* WEBKIT_DISABLE_DMABUF_RENDERER=1 fixes black screen on modern WebKitGTK 2.40-2.52 with Wayland / NVIDIA / Mesa */
    setenv("WEBKIT_DISABLE_DMABUF_RENDERER", "1", 1);
    if (force_software_rendering) {
        setenv("WEBKIT_DISABLE_COMPOSITING_MODE", "1", 1);
    }

    /* Check if browser mode requested */
    if (launch_browser_mode) {
        const char *browsers[] = {
            "google-chrome-stable",
            "google-chrome",
            "chromium",
            "chromium-browser",
            "brave-browser",
            "microsoft-edge-stable",
            NULL
        };
        char app_url[512];
        if (app_state.target_url[0] != '\0') {
            snprintf(app_url, sizeof(app_url), "%s", app_state.target_url);
        } else {
            snprintf(app_url, sizeof(app_url), "http://%s:%d/", app_state.host, app_state.port);
        }
        for (int b = 0; browsers[b]; b++) {
            char *bpath = g_find_program_in_path(browsers[b]);
            if (bpath) {
                g_print("[RemoteViber] 使用系统浏览器独立应用窗口启动: %s\n", bpath);
                char *app_arg = g_strdup_printf("--app=%s", app_url);
                char *ud_arg = g_strdup_printf("--user-data-dir=%s/.config/remote-viber/profile", g_get_home_dir());
                pid_t bpid = fork();
                if (bpid == 0) {
                    setsid();
                    execl(bpath, browsers[b], app_arg, "--window-size=1600,960", ud_arg, NULL);
                    _exit(127);
                }
                g_free(app_arg);
                g_free(ud_arg);
                g_free(bpath);
                if (bpid > 0) return 0;
            }
        }
        g_print("[RemoteViber] 未检测到 Chrome/Chromium 浏览器，回退至原生 GTK+WebKit 模式。\n");
    }

    if (!gtk_init_check(&argc, &argv)) {
        g_printerr("[RemoteViber] 未检测到图形桌面环境 (DISPLAY 或 WAYLAND_DISPLAY 未设置)。\n"
                    "提示: 若在无显示器的服务器或 SSH 会话中，请直接运行控制端核心:\n"
                    "      ./dist-bin/viber-host-linux-x86_64 --port %d\n"
                    "并在外部浏览器中访问: https://<你的IP>:%d/\n",
                    app_state.port, app_state.port);
        return 1;
    }

    find_local_pairing_code(&app_state);

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
        /* Default to HTTPS in protocol v2 */
        snprintf(app_state.target_url, sizeof(app_state.target_url), "https://%s:%d/", app_state.host, app_state.port);
    }

    if (!app_state.explicit_url && is_port_open(app_state.host, app_state.port)) {
        if (probe_is_plain_http(app_state.host, app_state.port)) {
            snprintf(app_state.target_url, sizeof(app_state.target_url), "http://%s:%d/", app_state.host, app_state.port);
        } else {
            snprintf(app_state.target_url, sizeof(app_state.target_url), "https://%s:%d/", app_state.host, app_state.port);
        }
    }

    apply_dark_theme();

    /* Create top-level GtkWindow */
    app_state.window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(app_state.window), APP_TITLE);
    gtk_window_set_default_size(GTK_WINDOW(app_state.window), 1600, 960);
    gtk_widget_set_size_request(app_state.window, 640, 480);
    gtk_widget_add_events(app_state.window, GDK_SCROLL_MASK);
    gtk_window_maximize(GTK_WINDOW(app_state.window));
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
    gtk_header_bar_set_subtitle(GTK_HEADER_BAR(app_state.header_bar), "4-Grid Mosaic Terminal | Native Client");
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

    /* Quick Action Buttons on HeaderBar - Use standard unicode symbols that never render as tofu boxes */
    app_state.btn_quick_term = gtk_button_new_with_label("+ 启动终端 (Alt+N)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_quick_term), "action-btn");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_quick_term), "quick-term-btn");
    g_signal_connect(app_state.btn_quick_term, "clicked", G_CALLBACK(on_quick_term_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_quick_term);

    app_state.btn_launch_agent = gtk_button_new_with_label("⚡ 启动 Agent (Alt+A)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_launch_agent), "action-btn");
    g_signal_connect(app_state.btn_launch_agent, "clicked", G_CALLBACK(on_launch_agent_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_launch_agent);

    app_state.btn_mosaic = gtk_button_new_with_label("⊞ 拼图网格 (Alt+M)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_mosaic), "action-btn");
    g_signal_connect(app_state.btn_mosaic, "clicked", G_CALLBACK(on_mosaic_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_mosaic);

    app_state.btn_single = gtk_button_new_with_label("▶ 单终端 (Alt+\\)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_single), "action-btn");
    g_signal_connect(app_state.btn_single, "clicked", G_CALLBACK(on_single_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_single);

    app_state.btn_dashboard = gtk_button_new_with_label("☰ 仪表盘 (Alt+D)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_dashboard), "action-btn");
    g_signal_connect(app_state.btn_dashboard, "clicked", G_CALLBACK(on_dashboard_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_dashboard);

    app_state.btn_pairing = gtk_button_new_with_label("配对凭据");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_pairing), "action-btn");
    g_signal_connect(app_state.btn_pairing, "clicked", G_CALLBACK(on_pairing_clicked), &app_state);
    gtk_header_bar_pack_start(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_pairing);

    /* End buttons */
    app_state.btn_quit = gtk_button_new_with_label("退出 (Ctrl+Q)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_quit), "action-btn");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_quit), "quit-btn");
    g_signal_connect(app_state.btn_quit, "clicked", G_CALLBACK(on_quit_clicked), &app_state);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_quit);

    app_state.btn_fullscreen = gtk_button_new_with_label("全屏 (F11)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_fullscreen), "action-btn");
    g_signal_connect(app_state.btn_fullscreen, "clicked", G_CALLBACK(on_fullscreen_clicked), &app_state);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_fullscreen);

    app_state.btn_reload = gtk_button_new_with_label("刷新 (Ctrl+R)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.btn_reload), "action-btn");
    g_signal_connect(app_state.btn_reload, "clicked", G_CALLBACK(on_reload_clicked), &app_state);
    gtk_header_bar_pack_end(GTK_HEADER_BAR(app_state.header_bar), app_state.btn_reload);

    gtk_window_set_titlebar(GTK_WINDOW(app_state.window), app_state.header_bar);

    /* Main Stack Container */
    app_state.stack = gtk_stack_new();
    gtk_stack_set_transition_type(GTK_STACK(app_state.stack), GTK_STACK_TRANSITION_TYPE_CROSSFADE);
    gtk_stack_set_transition_duration(GTK_STACK(app_state.stack), 250);

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
        "<span size='medium' weight='bold' foreground='#38bdf8'>正在等待连接服务端 (%s)...</span>\n\n"
        "<span size='small' foreground='#94a3b8'>服务端尚未启动。点击下方绿色按钮可直接在 GUI 启动：</span>\n\n"
        "<span font_family='monospace' size='medium' foreground='#34d399'>  ./dist-bin/viber-host-linux-x86_64 --port %d  </span>\n\n"
        "<span size='small' foreground='#64748b'>服务端启动后客户端将毫秒级自动感应并呈现拼图终端</span>",
        app_state.target_url, app_state.port);
    gtk_label_set_markup(GTK_LABEL(app_state.spinner_label), initial_wait_text);
    gtk_box_pack_start(GTK_BOX(glass_card), app_state.spinner_label, FALSE, FALSE, 4);

    /* Action button row */
    GtkWidget *btn_row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    gtk_widget_set_halign(btn_row, GTK_ALIGN_CENTER);

    app_state.start_host_btn = gtk_button_new_with_label("⚡ 一键启动本地服务端");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.start_host_btn), "start-host-btn");
    g_signal_connect(app_state.start_host_btn, "clicked", G_CALLBACK(on_start_host_clicked), &app_state);
    gtk_box_pack_start(GTK_BOX(btn_row), app_state.start_host_btn, FALSE, FALSE, 0);

    app_state.retry_btn = gtk_button_new_with_label("🔄 重试检测 (R)");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.retry_btn), "retry-btn");
    g_signal_connect(app_state.retry_btn, "clicked", G_CALLBACK(on_retry_clicked), &app_state);
    gtk_box_pack_start(GTK_BOX(btn_row), app_state.retry_btn, FALSE, FALSE, 0);

    GtkWidget *btn_proto = gtk_button_new_with_label("⚙️ 切换 HTTP/HTTPS");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_proto), "secondary-btn");
    g_signal_connect(btn_proto, "clicked", G_CALLBACK(on_toggle_protocol_clicked), &app_state);
    gtk_box_pack_start(GTK_BOX(btn_row), btn_proto, FALSE, FALSE, 0);

    app_state.open_browser_btn = gtk_button_new_with_label("🌐 系统浏览器打开");
    gtk_style_context_add_class(gtk_widget_get_style_context(app_state.open_browser_btn), "secondary-btn");
    g_signal_connect(app_state.open_browser_btn, "clicked", G_CALLBACK(on_open_browser_clicked), &app_state);
    gtk_box_pack_start(GTK_BOX(btn_row), app_state.open_browser_btn, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(glass_card), btn_row, FALSE, FALSE, 6);

    gtk_box_pack_start(GTK_BOX(app_state.loading_box), glass_card, FALSE, FALSE, 0);
    gtk_stack_add_named(GTK_STACK(app_state.stack), app_state.loading_box, "loading");

    /* WebKitWebView Setup (Software rendering policy guarantees zero DRI3 crash or black screen) */
    WebKitWebContext *ctx = webkit_web_context_get_default();
    webkit_web_context_set_sandbox_enabled(ctx, FALSE);

    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    webkit_web_context_set_tls_errors_policy(ctx, WEBKIT_TLS_ERRORS_POLICY_IGNORE);
    G_GNUC_END_IGNORE_DEPRECATIONS

    WebKitWebsiteDataManager *manager = webkit_web_context_get_website_data_manager(ctx);
    if (manager) {
        webkit_website_data_manager_set_tls_errors_policy(manager, WEBKIT_TLS_ERRORS_POLICY_IGNORE);
    }

    WebKitSettings *settings = webkit_settings_new();
    webkit_settings_set_enable_javascript(settings, TRUE);
    webkit_settings_set_enable_developer_extras(settings, TRUE);
    webkit_settings_set_enable_page_cache(settings, TRUE);
    webkit_settings_set_hardware_acceleration_policy(settings,
        force_software_rendering ? WEBKIT_HARDWARE_ACCELERATION_POLICY_NEVER : WEBKIT_HARDWARE_ACCELERATION_POLICY_ON_DEMAND);
    webkit_settings_set_zoom_text_only(settings, FALSE);

    app_state.web_view = webkit_web_view_new_with_context(ctx);
    webkit_web_view_set_settings(WEBKIT_WEB_VIEW(app_state.web_view), settings);
    g_object_unref(settings);

    if (app_state.zoom_level < 0.5) app_state.zoom_level = 1.0;
    webkit_web_view_set_zoom_level(WEBKIT_WEB_VIEW(app_state.web_view), app_state.zoom_level);

    /* Set dark base background */
    GdkRGBA dark_bg = { 0.035, 0.05, 0.09, 1.0 }; /* #090d16 */
    webkit_web_view_set_background_color(WEBKIT_WEB_VIEW(app_state.web_view), &dark_bg);

    /* Inject user dark theme CSS */
    WebKitUserContentManager *ucm = webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(app_state.web_view));
    WebKitUserStyleSheet *sheet = webkit_user_style_sheet_new(
        "html, body { background-color: #090d16 !important; color-scheme: dark !important; }"
        "::-webkit-scrollbar { width: 8px; height: 8px; }"
        "::-webkit-scrollbar-track { background: #090d16; }"
        "::-webkit-scrollbar-thumb { background: #1e293b; border-radius: 4px; }"
        "::-webkit-scrollbar-thumb:hover { background: #334155; }",
        WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
        WEBKIT_USER_STYLE_LEVEL_USER,
        NULL, NULL
    );
    webkit_user_content_manager_add_style_sheet(ucm, sheet);
    webkit_user_style_sheet_unref(sheet);

    /* Inject auto-pairing credential script at document start if connecting to localhost */
    find_local_pairing_code(&app_state);
    if (app_state.local_pairing_code[0] != '\0') {
        char *js_init = g_strdup_printf(
            "(function() {"
            "  try {"
            "    sessionStorage.setItem('viber_local_pairing_code', '%s');"
            "    var raw = sessionStorage.getItem('viber_host_config_v2');"
            "    if (!raw) {"
            "      var b = '%s'.trim().replace(/-/g, '+').replace(/_/g, '/');"
            "      var padded = b + '='.repeat((4 - b.length %% 4) %% 4);"
            "      var bin = atob(padded);"
            "      var bytes = new Uint8Array(bin.length);"
            "      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);"
            "      var d = JSON.parse(new TextDecoder('utf-8').decode(bytes));"
            "      if (d && d.v === 2 && d.pub && d.token) {"
            "        sessionStorage.setItem('viber_host_config_v2', JSON.stringify({"
            "          protocolVersion: 2,"
            "          hostId: d.id,"
            "          hostPub: d.pub,"
            "          hostName: d.name || 'Local Host',"
            "          directPort: d.port || %d,"
            "          token: d.token,"
            "          tailscaleIps: d.tailscale || [],"
            "          lanIps: d.lan || ['127.0.0.1'],"
            "          relayUrl: d.relay || '',"
            "          directUrl: d.direct_url || '',"
            "          ssl: Boolean(d.ssl),"
            "          fingerprint: (d.id || '').replace(/^host-/, '')"
            "        }));"
            "      }"
            "    }"
            "  } catch(e) {}"
            "})();",
            app_state.local_pairing_code, app_state.local_pairing_code, app_state.port
        );

        WebKitUserScript *user_script = webkit_user_script_new(
            js_init,
            WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
            WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
            NULL, NULL
        );
        webkit_user_content_manager_add_script(ucm, user_script);
        webkit_user_script_unref(user_script);
        g_free(js_init);
    }

    app_state.inspector = webkit_web_view_get_inspector(WEBKIT_WEB_VIEW(app_state.web_view));

    /* Connect WebKit events */
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
    g_signal_connect(app_state.window, "scroll-event", G_CALLBACK(on_scroll_event), &app_state);
    g_signal_connect(app_state.window, "destroy", G_CALLBACK(on_window_destroy), &app_state);

    gtk_widget_show_all(app_state.window);

    g_print("[RemoteViber] 原生桌面工作台已启动 (PID: %d)\n", getpid());
    g_print("[RemoteViber] 目标连接地址: %s\n", app_state.target_url);
    fflush(stdout);

    find_local_pairing_code(&app_state);

    /* Initial check */
    if (check_host_ready_cb(&app_state) == G_SOURCE_CONTINUE) {
        start_poll_timer(&app_state, 1000);
    }

    if (detach_to_background) {
        pid_t pid = fork();
        if (pid > 0) {
            g_print("[RemoteViber] 客户端已成功转入后台独立运行 (PID: %d)。当前终端已释放。\n", pid);
            fflush(stdout);
            return 0;
        } else if (pid == 0) {
            setsid();
        }
    }

    gtk_main();

    return 0;
}
