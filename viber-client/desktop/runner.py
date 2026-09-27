"""
RemoteViber Native Desktop Runner (Windows & Linux Graphical Desktop)
Launches the Host Daemon and spawns a dedicated standalone application window
with hardware acceleration and zero unnecessary memory overhead.
"""

import os
import sys
import time
import shutil
import subprocess
import webbrowser


def find_browser_app_binary():
    """Locates Chrome, Edge, Chromium, or Brave for native frameless app-mode window."""
    candidates = [
        # Windows candidates
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        # Linux candidates
        "google-chrome",
        "google-chrome-stable",
        "chromium-browser",
        "chromium",
        "brave-browser",
        "microsoft-edge",
    ]

    for candidate in candidates:
        if sys.platform == "win32":
            if os.path.exists(candidate):
                return candidate
        else:
            path = shutil.which(candidate)
            if path:
                return path
    return None


def main():
    port = 8765
    url = f"http://127.0.0.1:{port}"

    # Check if host daemon is already running
    import urllib.request
    host_running = False
    try:
        with urllib.request.urlopen(f"{url}/api/pairing", timeout=1.0) as resp:
            if resp.status == 200:
                host_running = True
    except Exception:
        host_running = False

    daemon_proc = None
    if not host_running:
        print("⚡ Starting RemoteViber Host Daemon in background...")
        host_main = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../viber-host/main.py"))
        python_bin = sys.executable

        daemon_proc = subprocess.Popen(
            [python_bin, host_main],
            cwd=os.path.dirname(host_main),
            stdout=subprocess.DEVNULL if sys.platform != "win32" else None,
            stderr=subprocess.DEVNULL if sys.platform != "win32" else None,
        )
        time.sleep(1.2)

    # Launch Standalone Native App Window
    browser_bin = find_browser_app_binary()
    if browser_bin:
        print(f"🖥️  Opening RemoteViber Desktop Client window ({browser_bin})...")
        cmd = [browser_bin, f"--app={url}", "--window-size=1200,800"]
        try:
            subprocess.Popen(cmd)
        except Exception:
            webbrowser.open(url)
    else:
        print(f"🖥️  Opening RemoteViber in default browser ({url})...")
        webbrowser.open(url)

    if daemon_proc:
        try:
            daemon_proc.wait()
        except KeyboardInterrupt:
            daemon_proc.terminate()


if __name__ == "__main__":
    main()
