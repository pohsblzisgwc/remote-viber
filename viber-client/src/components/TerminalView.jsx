import React, { useEffect, useRef, useState } from 'react';
import { Terminal as XTerminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import { RefreshCw, Terminal as TerminalIcon } from 'lucide-react';
import MobileToolbar from './MobileToolbar';

function stringToBase64(str) {
  const bytes = new TextEncoder().encode(str);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return window.btoa(binary);
}

function base64ToString(b64) {
  const binary = window.atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}

export default function TerminalView({
  connection,
  activeSession,
  connectionState,
  onSwitchToDashboard,
  onTerminateSession,
  onRestartSession,
  onDeleteSession,
}) {
  const containerRef = useRef(null);
  const xtermRef = useRef(null);
  const fitAddonRef = useRef(null);
  const [isReady, setIsReady] = useState(false);
  const [fontSize, setFontSize] = useState(() => {
    return (typeof window !== 'undefined' && window.innerWidth < 640) ? 11 : 13;
  });
  const [preventAltScreen, setPreventAltScreen] = useState(false);
  const preventAltScreenRef = useRef(preventAltScreen);
  preventAltScreenRef.current = preventAltScreen;

  const handleZoomIn = () => {
    setFontSize((prev) => {
      const next = Math.min(prev + 1, 22);
      if (xtermRef.current) {
        xtermRef.current.options.fontSize = next;
        setTimeout(() => {
          fitAddonRef.current?.fit();
          if (connection && activeSession && xtermRef.current?.rows) {
            connection.resizeTerminal(activeSession.session_id, xtermRef.current.rows, xtermRef.current.cols);
          }
        }, 30);
      }
      return next;
    });
  };

  const handleZoomOut = () => {
    setFontSize((prev) => {
      const next = Math.max(prev - 1, 9);
      if (xtermRef.current) {
        xtermRef.current.options.fontSize = next;
        setTimeout(() => {
          fitAddonRef.current?.fit();
          if (connection && activeSession && xtermRef.current?.rows) {
            connection.resizeTerminal(activeSession.session_id, xtermRef.current.rows, xtermRef.current.cols);
          }
        }, 30);
      }
      return next;
    });
  };

  // Mobile wake-up / tab switch auto-reconnect & viewport fit
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        if (connection && connectionState !== 'connected' && connection.shouldReconnect !== false) {
          connection.connect();
        }
        setTimeout(() => {
          try {
            fitAddonRef.current?.fit();
          } catch (e) {}
        }, 150);
      }
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [connection, connectionState]);

  useEffect(() => {
    if (!containerRef.current || !activeSession) return;

    const isMobile = typeof window !== 'undefined' && window.innerWidth < 640;

    // Create fresh Xterm instance with 50,000 lines scrollback and alternateScreenScroll
    const term = new XTerminal({
      cursorBlink: true,
      cursorStyle: 'bar',
      fontSize: fontSize,
      lineHeight: isMobile ? 1.15 : 1.2,
      fontFamily: "'Fira Code', Menlo, Monaco, 'Courier New', monospace",
      theme: {
        background: '#090d16',
        foreground: '#e2e8f0',
        cursor: '#38bdf8',
        cursorAccent: '#090d16',
        selectionBackground: 'rgba(56, 189, 248, 0.25)',
        black: '#1e293b',
        red: '#f43f5e',
        green: '#10b981',
        yellow: '#f59e0b',
        blue: '#3b82f6',
        magenta: '#a855f7',
        cyan: '#06b6d4',
        white: '#f8fafc',
        brightBlack: '#475569',
        brightRed: '#fb7185',
        brightGreen: '#34d399',
        brightYellow: '#fbbf24',
        brightBlue: '#60a5fa',
        brightMagenta: '#c084fc',
        brightCyan: '#22d3ee',
        brightWhite: '#ffffff',
      },
      scrollback: 50000,
      alternateScreenScroll: true,
      scrollOnUserInput: true,
      allowProposedApi: true,
    });

    // Guard scrollback: prevent CLI commands from wiping scrollback buffer (CSI 3J)
    try {
      term.parser.registerCsiHandler({ prefix: '', final: 'J' }, (params) => {
        if (params[0] === 3) return true; // Block clearing scrollback
        return false;
      });

      // Anti-Truncation: when enabled, suppress entering alternate screen buffer (1049 / 47)
      // This forces full-screen TUI (like Codex) into the primary buffer where scrollback and scrollbar never vanish
      term.parser.registerCsiHandler({ prefix: '?', final: 'h' }, (params) => {
        if (preventAltScreenRef.current && (params[0] === 1049 || params[0] === 47)) {
          return true;
        }
        return false;
      });

      term.parser.registerCsiHandler({ prefix: '?', final: 'l' }, (params) => {
        if (preventAltScreenRef.current && (params[0] === 1049 || params[0] === 47)) {
          return true;
        }
        return false;
      });
    } catch (e) {}

    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(containerRef.current);
    fitAddon.fit();

    // Synchronize actual terminal dimensions immediately so PTY child process (e.g. Codex)
    // gets the true viewport dimensions on launch rather than default 24x80
    if (connection && activeSession && term.rows && term.cols) {
      connection.resizeTerminal(activeSession.session_id, term.rows, term.cols);
    }

    xtermRef.current = term;
    fitAddonRef.current = fitAddon;
    setIsReady(true);

    // Forward terminal input to backend PTY
    const onDataDisposable = term.onData((data) => {
      if (connection && activeSession) {
        const b64 = stringToBase64(data);
        connection.sendInput(activeSession.session_id, b64);
      }
    });

    // Window & Mobile Virtual Viewport resize observer
    const handleFit = () => {
      try {
        fitAddon.fit();
        if (connection && activeSession && term.rows && term.cols) {
          connection.resizeTerminal(activeSession.session_id, term.rows, term.cols);
        }
      } catch (e) {}
    };

    const resizeObserver = new ResizeObserver(handleFit);
    resizeObserver.observe(containerRef.current);

    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', handleFit);
    }

    // Handle incoming terminal output from PTY
    const unsubOutput = connection.on('terminal_output', (msg) => {
      if (msg.session_id === activeSession.session_id && msg.data) {
        try {
          const text = base64ToString(msg.data);
          term.write(text);
        } catch (e) {
          console.error('Error writing terminal output:', e);
        }
      }
    });

    // Handle session attached & buffer replay
    const unsubAttached = connection.on('session_attached', (msg) => {
      if (msg.session.session_id === activeSession.session_id) {
        if (msg.needs_reset) {
          term.reset();
        }
        if (msg.replay && Array.isArray(msg.replay)) {
          // Batch replay text into consolidated writes to prevent freezing the UI thread on long histories
          let batch = '';
          for (let i = 0; i < msg.replay.length; i++) {
            try {
              batch += base64ToString(msg.replay[i].data);
            } catch (e) {}
            if (batch.length >= 65536 || i === msg.replay.length - 1) {
              if (batch) {
                term.write(batch);
                batch = '';
              }
            }
          }
        }
      }
    });

    // Attach to session
    connection.attachSession(activeSession.session_id, 0);

    return () => {
      onDataDisposable.dispose();
      resizeObserver.disconnect();
      if (window.visualViewport) {
        window.visualViewport.removeEventListener('resize', handleFit);
      }
      unsubOutput();
      unsubAttached();
      term.dispose();
    };
  }, [activeSession?.session_id]);

  const handleSendKey = (chars) => {
    if (connection && activeSession && chars) {
      const b64 = stringToBase64(chars);
      connection.sendInput(activeSession.session_id, b64);
    }
  };

  const handleSendPrompt = (prompt) => {
    if (connection && activeSession && prompt) {
      const b64 = stringToBase64(prompt);
      connection.sendInput(activeSession.session_id, b64);
    }
  };

  if (!activeSession) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-6 text-center">
        <div className="w-14 h-14 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-center text-slate-500 mb-4">
          <TerminalIcon className="w-7 h-7" />
        </div>
        <h3 className="font-semibold text-slate-200">当前未连接终端会话</h3>
        <p className="text-xs text-slate-500 max-w-sm mt-1 mb-4">
          请从控制面板中选择一个活动中的 Agent/终端，或一键拉起新会话。
        </p>
        <button
          onClick={onSwitchToDashboard}
          className="px-4 py-2 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white font-medium text-xs shadow-md shadow-cyan-900/20"
        >
          返回控制面板
        </button>
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col relative overflow-hidden bg-[#090d16]">

      {/* Network drop / reconnection banner */}
      {connectionState !== 'connected' && (
        <div className="bg-amber-500/10 border-b border-amber-500/30 px-3 py-1.5 flex items-center justify-between text-xs text-amber-300 font-mono z-20">
          <div className="flex items-center gap-2">
            <RefreshCw className="w-3.5 h-3.5 animate-spin" />
            <span>网络连接中断 — 宿主会话与 Agent 正在后台安全运行，数据零丢失。正在自动重连...</span>
          </div>
          <span className="text-[10px] text-amber-400/80 uppercase">进程持续保活</span>
        </div>
      )}

      {/* Session Quick Status & Anti-Truncate Switch */}
      <div className="flex items-center justify-between px-3 py-1 bg-[#070b14] border-b border-slate-800/80 text-[11px] text-slate-400 font-mono z-10 shrink-0">
        <div className="flex items-center gap-2 truncate">
          <span className="text-slate-500">会话:</span>
          <span className="text-cyan-400 font-medium truncate max-w-[120px] sm:max-w-[200px]">{activeSession.name}</span>
          <span className="text-slate-600 hidden sm:inline">|</span>
          <span className="text-slate-500 hidden sm:inline">PID:</span>
          <span className="hidden sm:inline">{activeSession.pid || '-'}</span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            onClick={() => setPreventAltScreen(!preventAltScreen)}
            title="强制锁定主屏幕缓冲区：拦截全屏 TUI (如 Codex/Ratatui) 切换备用屏，确保侧边滚动条永不消失、长对话历史永不截断丢失"
            className={`px-2 py-0.5 rounded text-[10px] font-medium border transition-all flex items-center gap-1.5 cursor-pointer ${
              preventAltScreen
                ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/60 shadow-sm shadow-cyan-900/30'
                : 'bg-slate-800/60 text-slate-400 border-slate-700/60 hover:text-slate-200 hover:border-slate-600'
            }`}
          >
            <span>🛡️ 防截断回滚模式</span>
            <span className={`w-1.5 h-1.5 rounded-full ${preventAltScreen ? 'bg-cyan-400 animate-pulse' : 'bg-slate-600'}`} />
          </button>
        </div>
      </div>

      {/* Terminal Viewport */}
      <div className="flex-1 relative p-1 md:p-2 overflow-hidden">
        <div ref={containerRef} className="w-full h-full" />
      </div>

      {/* Mobile Touch Toolbar & Quick Prompter */}
      <MobileToolbar 
        onSendKey={handleSendKey} 
        onSendPrompt={handleSendPrompt}
        onZoomIn={handleZoomIn}
        onZoomOut={handleZoomOut}
      />
    </div>
  );
}
