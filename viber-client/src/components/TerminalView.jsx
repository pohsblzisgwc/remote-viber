import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Terminal as XTerminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import {
  RefreshCw,
  Terminal as TerminalIcon,
  ChevronsUp,
  ChevronsDown,
  ChevronUp,
  ChevronDown,
  ArrowDownToLine,
  Download,
  History,
} from 'lucide-react';
import MobileToolbar from './MobileToolbar';
import SafePasteModal from './SafePasteModal';

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
  const [isInitialScrollReady, setIsInitialScrollReady] = useState(false);
  const [linesScrolledUp, setLinesScrolledUp] = useState(0);
  const [fontSize, setFontSize] = useState(() => {
    return (typeof window !== 'undefined' && window.innerWidth < 640) ? 11 : 13;
  });
  const [preventAltScreen, setPreventAltScreen] = useState(() => {
    try {
      const saved = localStorage.getItem('viber_prevent_alt_screen');
      return saved !== null ? saved === 'true' : true;
    } catch (e) {
      return true;
    }
  });
  const preventAltScreenRef = useRef(preventAltScreen);
  preventAltScreenRef.current = preventAltScreen;

  const [syncFullHistory, setSyncFullHistory] = useState(() => {
    try {
      const saved = localStorage.getItem('viber_sync_full_history');
      return saved !== null ? saved === 'true' : false;
    } catch (e) {
      return false;
    }
  });
  const syncFullHistoryRef = useRef(syncFullHistory);
  syncFullHistoryRef.current = syncFullHistory;

  const [isHistoryTruncated, setIsHistoryTruncated] = useState(false);
  const [pasteModal, setPasteModal] = useState(null);

  const handleConfirmSafePaste = () => {
    if (!pasteModal || !connection || !activeSession) return;
    const bracketed = `\x1b[200~${pasteModal.text}\x1b[201~`;
    connection.sendInput(activeSession.session_id, stringToBase64(bracketed));
    setPasteModal(null);
  };

  const handleConfirmSingleLinePaste = () => {
    if (!pasteModal || !connection || !activeSession) return;
    const singleLine = pasteModal.text.replace(/[\r\n]+/g, '; ');
    connection.sendInput(activeSession.session_id, stringToBase64(singleLine));
    setPasteModal(null);
  };

  const handlePasteText = useCallback((clipboardText) => {
    if (!connection || !activeSession || !clipboardText) return;

    if (/[\r\n]/.test(clipboardText)) {
      // 1. Strip trailing newlines (so a single line command never auto-executes)
      const trimmed = clipboardText.replace(/[\r\n]+$/, '');

      // If after stripping trailing newlines there are no remaining newlines:
      if (!/[\r\n]/.test(trimmed)) {
        connection.sendInput(activeSession.session_id, stringToBase64(trimmed));
        return;
      }

      // 2. Multi-line paste: if terminal has bracketed paste mode active, wrap and send safely
      if (xtermRef.current?.modes?.bracketedPasteMode) {
        const bracketed = `\x1b[200~${trimmed}\x1b[201~`;
        connection.sendInput(activeSession.session_id, stringToBase64(bracketed));
        return;
      }

      // 3. Otherwise show confirmation modal to prevent accidental execution
      setPasteModal({
        text: trimmed,
        linesCount: trimmed.split(/\r?\n/).length,
      });
    } else {
      connection.sendInput(activeSession.session_id, stringToBase64(clipboardText));
    }
  }, [connection, activeSession]);

  const handleToggleSyncFullHistory = () => {
    setSyncFullHistory((prev) => {
      const next = !prev;
      try {
        localStorage.setItem('viber_sync_full_history', String(next));
      } catch (e) {}
      if (next && connection && activeSession) {
        setIsInitialScrollReady(false);
        connection.attachSession(activeSession.session_id, 0, true);
      }
      return next;
    });
  };

  const handleLoadAllHistoryNow = () => {
    if (connection && activeSession) {
      setIsInitialScrollReady(false);
      connection.attachSession(activeSession.session_id, 0, true);
    }
  };

  const handleToggleAltScreen = () => {
    setPreventAltScreen((prev) => {
      const next = !prev;
      try {
        localStorage.setItem('viber_prevent_alt_screen', String(next));
      } catch (e) {}
      if (next && xtermRef.current) {
        try {
          xtermRef.current.write('\x1b[?1049l');
        } catch (e) {}
      }
      return next;
    });
  };

  const handleScrollToTop = () => {
    xtermRef.current?.scrollToTop();
  };

  const handleScrollPageUp = () => {
    xtermRef.current?.scrollLines(-25);
  };

  const handleScrollPageDown = () => {
    xtermRef.current?.scrollLines(25);
  };

  const handleScrollToBottom = () => {
    xtermRef.current?.scrollToBottom();
    setLinesScrolledUp(0);
  };

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
    setIsInitialScrollReady(false);

    const isMobile = typeof window !== 'undefined' && window.innerWidth < 640;

    // Create fresh Xterm instance with 10,000 lines scrollback and ultra-responsive scrolling
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
      scrollback: 10000,
      scrollSensitivity: 3,
      fastScrollSensitivity: 8,
      smoothScrollDuration: 0,
      alternateScreenScroll: false,
      scrollOnUserInput: true,
      allowProposedApi: true,
    });

    // Guard scrollback: prevent CLI commands from wiping scrollback buffer (CSI 3J)
    try {
      term.parser.registerCsiHandler({ prefix: '', final: 'J' }, (params) => {
        if (params[0] === 3) return true; // Block clearing scrollback
        return false;
      });

      // Anti-Truncation: when enabled, suppress entering alternate screen buffer (1049 / 47 / 1047)
      // This forces full-screen TUI (like Codex / Ratatui) into the primary buffer where scrollback and scrollbar never vanish
      term.parser.registerCsiHandler({ prefix: '?', final: 'h' }, (params) => {
        if (preventAltScreenRef.current && (params[0] === 1049 || params[0] === 47 || params[0] === 1047)) {
          return true;
        }
        return false;
      });
    } catch (e) {}

    // Custom wheel handler: high-speed local 60fps scrolling without PTY lag
    try {
      term.attachCustomWheelEventHandler((e) => {
        if (e.ctrlKey) return true; // Let browser zoom work
        if (preventAltScreenRef.current || e.shiftKey) {
          const multiplier = e.shiftKey ? 16 : 4;
          term.scrollLines(e.deltaY > 0 ? multiplier : -multiplier);
          return false;
        }
        return true;
      });
    } catch (e) {}

    // Custom key handler: PageUp / PageDown / Shift+Home / Shift+End
    try {
      term.attachCustomKeyEventHandler((e) => {
        if (e.type === 'keydown') {
          if (e.key === 'PageUp' && (e.shiftKey || preventAltScreenRef.current)) {
            term.scrollPages(-1);
            return false;
          }
          if (e.key === 'PageDown' && (e.shiftKey || preventAltScreenRef.current)) {
            term.scrollPages(1);
            return false;
          }
          if (e.key === 'Home' && (e.shiftKey || e.ctrlKey)) {
            term.scrollToTop();
            return false;
          }
          if (e.key === 'End' && (e.shiftKey || e.ctrlKey)) {
            term.scrollToBottom();
            return false;
          }
        }
        return true;
      });
    } catch (e) {}

    let scrollRafId = null;
    const scrollDisposable = term.onScroll(() => {
      if (scrollRafId) return;
      scrollRafId = requestAnimationFrame(() => {
        scrollRafId = null;
        if (!xtermRef.current) return;
        const currentTerm = xtermRef.current;
        const baseY = currentTerm.buffer?.active?.baseY || 0;
        const viewportY = currentTerm.buffer?.active?.viewportY || 0;
        const diff = Math.max(0, baseY - viewportY);
        setLinesScrolledUp((prev) => (prev !== diff ? diff : prev));
      });
    });

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

    // Forward terminal input to backend PTY
    const onDataDisposable = term.onData((data) => {
      if (connection && activeSession) {
        const b64 = stringToBase64(data);
        connection.sendInput(activeSession.session_id, b64);
      }
    });

    // Intercept DOM paste events to prevent newlines from auto-executing commands
    const handlePaste = (e) => {
      const clipboardText = e.clipboardData?.getData('text/plain') || '';
      if (!clipboardText) return;

      if (/[\r\n]/.test(clipboardText)) {
        e.preventDefault();
        e.stopPropagation();
        handlePasteText(clipboardText);
      }
    };

    const containerEl = containerRef.current;
    containerEl?.addEventListener('paste', handlePaste, true);

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

    let isReplaying = true;
    let replayTimer = null;
    let pendingBatch = '';

    const flushBatch = () => {
      if (!pendingBatch) return;
      const data = pendingBatch;
      pendingBatch = '';
      term.write(data, () => {
        try {
          term.scrollToBottom();
        } catch (e) {}
      });
    };

    const finishReplay = () => {
      if (!isReplaying) return;
      flushBatch();
      isReplaying = false;
      try {
        term.scrollToBottom();
      } catch (e) {}
      setIsInitialScrollReady(true);
    };

    // Failsafe timer to reveal terminal even if no replay output is received
    replayTimer = setTimeout(finishReplay, 100);

    const writeChunk = (text) => {
      if (isReplaying) {
        pendingBatch += text;
        clearTimeout(replayTimer);
        if (pendingBatch.length >= 32768) {
          flushBatch();
        }
        replayTimer = setTimeout(finishReplay, 45);
      } else {
        term.write(text);
      }
    };

    // Handle incoming terminal output from PTY
    const unsubOutput = connection.on('terminal_output', (msg) => {
      if (msg.session_id === activeSession.session_id && msg.data) {
        try {
          const text = base64ToString(msg.data);
          writeChunk(text);
        } catch (e) {
          console.error('Error writing terminal output:', e);
        }
      }
    });

    // Handle session attached & buffer replay
    const unsubAttached = connection.on('session_attached', (msg) => {
      if (msg.session.session_id === activeSession.session_id) {
        setIsHistoryTruncated(Boolean(msg.is_truncated));
        if (msg.needs_reset) {
          term.reset();
        }
        if (msg.replay && Array.isArray(msg.replay) && msg.replay.length > 0) {
          let batch = '';
          for (let i = 0; i < msg.replay.length; i++) {
            try {
              batch += base64ToString(msg.replay[i].data);
            } catch (e) {}
          }
          if (batch) {
            writeChunk(batch);
          }
        }
      }
    });

    // Attach to session (default to truncated history unless user explicitly requested full history sync)
    connection.attachSession(activeSession.session_id, 0, syncFullHistoryRef.current);

    return () => {
      containerEl?.removeEventListener('paste', handlePaste, true);
      if (scrollRafId) cancelAnimationFrame(scrollRafId);
      scrollDisposable?.dispose?.();
      clearTimeout(replayTimer);
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
        <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
          {isHistoryTruncated && !syncFullHistory && (
            <button
              onClick={handleLoadAllHistoryNow}
              title="当前终端会话为保障秒开体验，默认截断了超长历史。点击一次性拉取并渲染缓冲区完整历史"
              className="px-2 py-0.5 rounded text-[10px] font-medium border border-amber-500/50 bg-amber-500/20 text-amber-300 hover:bg-amber-500/30 hover:border-amber-400 flex items-center gap-1 transition-all cursor-pointer shadow-sm shadow-amber-950/40"
            >
              <Download className="w-3 h-3 text-amber-400" />
              <span>📥 加载全部历史</span>
            </button>
          )}

          <button
            onClick={handleToggleSyncFullHistory}
            title="终端打开时同步策略：开启后每次打开或重连终端均拉取完整历史缓冲；默认关闭时截断历史以保证极速秒开。"
            className={`px-2.5 py-0.5 rounded text-[10px] font-medium border transition-all flex items-center gap-1.5 cursor-pointer ${
              syncFullHistory
                ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/60 shadow-sm shadow-emerald-900/30'
                : 'bg-slate-800/60 text-slate-400 border-slate-700/60 hover:text-slate-200 hover:border-slate-600'
            }`}
          >
            <History className="w-3 h-3" />
            <span className="hidden sm:inline">{syncFullHistory ? '📜 完整历史: 始终同步' : '📜 完整历史: 默认截断'}</span>
            <span className="sm:hidden">{syncFullHistory ? '📜 完整' : '📜 截断'}</span>
            <span className={`w-1.5 h-1.5 rounded-full ${syncFullHistory ? 'bg-emerald-400 animate-pulse' : 'bg-slate-600'}`} />
          </button>

          <button
            onClick={handleToggleAltScreen}
            title="锁定主屏幕缓冲区：拦截全屏 TUI (如 Codex/Ratatui) 切换备用屏，确保侧边 14px 宽滚动条永不消失、滚轮 60fps 平滑滚动与长对话历史完整保留"
            className={`px-2.5 py-0.5 rounded text-[10px] font-medium border transition-all flex items-center gap-1.5 cursor-pointer ${
              preventAltScreen
                ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/60 shadow-sm shadow-cyan-900/30'
                : 'bg-slate-800/60 text-slate-400 border-slate-700/60 hover:text-slate-200 hover:border-slate-600'
            }`}
          >
            <span>⚡ Codex 高速滚动与防截断</span>
            <span className={`w-1.5 h-1.5 rounded-full ${preventAltScreen ? 'bg-cyan-400 animate-pulse' : 'bg-slate-600'}`} />
          </button>
        </div>
      </div>

      {/* Terminal Viewport */}
      <div className="flex-1 relative p-1 md:p-2 overflow-hidden bg-[#090d16]">
        {/* Loading Indicator Overlay - 拒绝静默加载，提供清晰的同步状态动画 */}
        {!isInitialScrollReady && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center bg-[#090d16]/95 backdrop-blur-sm pointer-events-none select-none transition-opacity duration-200">
            <div className="relative flex items-center justify-center mb-4">
              <div className="w-12 h-12 rounded-full border-2 border-cyan-500/20 border-t-cyan-400 animate-spin" />
              <TerminalIcon className="w-5 h-5 text-cyan-400 absolute animate-pulse" />
            </div>
            <div className="flex items-center gap-2">
              <span className="inline-block w-2 h-2 rounded-full bg-cyan-400 animate-ping" />
              <span className="text-xs font-mono font-medium text-slate-200 tracking-wide">
                {syncFullHistory ? '正在拉取完整历史数据...' : '正在同步终端会话历史...'}
              </span>
            </div>
            <p className="text-[11px] font-mono text-slate-500 mt-1.5">
              {syncFullHistory
                ? '已开启完整历史同步，正在流式重放并渲染完整缓冲区...'
                : '已默认截断历史并优化上下文行数，秒级直达最新输出'}
            </p>
          </div>
        )}

        {/* Floating High-Speed Scroll Rail & Navigation Control */}
        {isInitialScrollReady && (
          <div className="absolute right-5 top-4 z-20 flex flex-col items-center bg-[#070d18]/90 backdrop-blur-md border border-slate-700/70 rounded-xl p-1 shadow-2xl transition-all group">
            <button
              onClick={handleScrollToTop}
              title="直达顶部 (Shift+Home)"
              className="w-7 h-7 flex items-center justify-center rounded-lg text-slate-400 hover:text-cyan-300 hover:bg-slate-800/80 active:scale-95 transition-all cursor-pointer"
            >
              <ChevronsUp className="w-4 h-4" />
            </button>
            <button
              onClick={handleScrollPageUp}
              title="高速向上翻页 (PageUp / Shift+滚轮)"
              className="w-7 h-7 flex items-center justify-center rounded-lg text-slate-400 hover:text-cyan-300 hover:bg-slate-800/80 active:scale-95 transition-all cursor-pointer"
            >
              <ChevronUp className="w-4 h-4" />
            </button>

            {/* Position / Offset Indicator */}
            {linesScrolledUp > 0 && (
              <div
                onClick={handleScrollToBottom}
                title="当前查看历史，点击回到底部"
                className="my-0.5 px-1 py-0.5 rounded text-[9px] font-mono font-bold bg-cyan-950/80 text-cyan-300 border border-cyan-500/40 cursor-pointer text-center leading-tight hover:bg-cyan-900 transition-colors"
              >
                +{linesScrolledUp > 999 ? `${(linesScrolledUp / 1000).toFixed(1)}k` : linesScrolledUp}
              </div>
            )}

            <button
              onClick={handleScrollPageDown}
              title="高速向下翻页 (PageDown)"
              className="w-7 h-7 flex items-center justify-center rounded-lg text-slate-400 hover:text-cyan-300 hover:bg-slate-800/80 active:scale-95 transition-all cursor-pointer"
            >
              <ChevronDown className="w-4 h-4" />
            </button>
            <button
              onClick={handleScrollToBottom}
              title="直达最新输出 (Shift+End)"
              className={`w-7 h-7 flex items-center justify-center rounded-lg active:scale-95 transition-all cursor-pointer ${
                linesScrolledUp > 0
                  ? 'text-cyan-400 hover:text-cyan-200 hover:bg-cyan-950/60 animate-pulse'
                  : 'text-slate-400 hover:text-cyan-300 hover:bg-slate-800/80'
              }`}
            >
              <ChevronsDown className="w-4 h-4" />
            </button>
          </div>
        )}

        {/* Floating Jump to Bottom Pill */}
        {isInitialScrollReady && linesScrolledUp > 0 && (
          <button
            onClick={handleScrollToBottom}
            className="absolute bottom-5 left-1/2 -translate-x-1/2 z-20 flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-cyan-950/95 border border-cyan-500/70 text-cyan-300 shadow-xl shadow-cyan-950/80 hover:bg-cyan-900 hover:border-cyan-400 text-xs font-mono transition-all animate-bounce cursor-pointer group backdrop-blur-md"
            title="点击直接跳回最新输出 (Shift+End)"
          >
            <ArrowDownToLine className="w-4 h-4 group-hover:translate-y-0.5 transition-transform" />
            <span>回到底部最新输出 (+{linesScrolledUp} 行)</span>
          </button>
        )}

        <div
          ref={containerRef}
          className={`w-full h-full transition-opacity duration-150 ${
            isInitialScrollReady ? 'opacity-100' : 'opacity-0 pointer-events-none'
          }`}
        />
      </div>

      {/* Mobile Touch Toolbar & Quick Prompter */}
      <MobileToolbar 
        onSendKey={handleSendKey} 
        onPaste={handlePasteText}
        onSendPrompt={handleSendPrompt}
        onZoomIn={handleZoomIn}
        onZoomOut={handleZoomOut}
      />

      {/* Safe Paste Multi-line Confirmation Modal */}
      <SafePasteModal
        data={pasteModal}
        onConfirmSafe={handleConfirmSafePaste}
        onConfirmSingleLine={handleConfirmSingleLinePaste}
        onCancel={() => setPasteModal(null)}
      />
    </div>
  );
}
