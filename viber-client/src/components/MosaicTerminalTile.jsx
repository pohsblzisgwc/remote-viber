import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Terminal as XTerminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import {
  GripVertical,
  Maximize2,
  Minimize2,
  Columns2,
  Rows2,
  X,
  Terminal as TerminalIcon,
  ChevronsUp,
  ChevronsDown,
  ArrowDownToLine,
  Download,
  History,
  Sparkles,
  ArrowRightLeft,
} from 'lucide-react';

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

export default function MosaicTerminalTile({
  session,
  connection,
  isFocused,
  onFocus,
  isMaximized,
  onToggleMaximize,
  onSplitRight,
  onSplitDown,
  onClose,
  index,
  totalTiles,
  onDragStart,
  onDragOverTile,
  onDragLeaveTile,
  onDropOnTile,
  dragState, // { draggedId, targetId, zone }
}) {
  const containerRef = useRef(null);
  const tileRootRef = useRef(null);
  const xtermRef = useRef(null);
  const fitAddonRef = useRef(null);

  const [isInitialReady, setIsInitialReady] = useState(false);
  const [linesScrolledUp, setLinesScrolledUp] = useState(0);
  const [isHistoryTruncated, setIsHistoryTruncated] = useState(false);

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

  // Auto focus terminal instance when isFocused changes
  useEffect(() => {
    if (isFocused && xtermRef.current) {
      try {
        xtermRef.current.focus();
      } catch (e) {}
    }
  }, [isFocused]);

  const handleToggleAltScreen = (e) => {
    e.stopPropagation();
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

  const handleToggleSyncFullHistory = (e) => {
    e.stopPropagation();
    setSyncFullHistory((prev) => {
      const next = !prev;
      try {
        localStorage.setItem('viber_sync_full_history', String(next));
      } catch (e) {}
      if (next && connection && session) {
        setIsInitialReady(false);
        connection.attachSession(session.session_id, 0, true);
      }
      return next;
    });
  };

  const handleLoadAllHistoryNow = (e) => {
    e.stopPropagation();
    if (connection && session) {
      setIsInitialReady(false);
      connection.attachSession(session.session_id, 0, true);
    }
  };

  const handleScrollToTop = () => {
    xtermRef.current?.scrollToTop();
  };

  const handleScrollToBottom = () => {
    xtermRef.current?.scrollToBottom();
    setLinesScrolledUp(0);
  };

  // Initialize and mount Xterm for this specific tile
  useEffect(() => {
    if (!containerRef.current || !session) return;
    setIsInitialReady(false);

    const term = new XTerminal({
      cursorBlink: true,
      cursorStyle: 'bar',
      fontSize: 12,
      lineHeight: 1.18,
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
      scrollback: 8000,
      scrollSensitivity: 3,
      fastScrollSensitivity: 8,
      smoothScrollDuration: 0,
      alternateScreenScroll: false,
      scrollOnUserInput: true,
      allowProposedApi: true,
    });

    try {
      term.parser.registerCsiHandler({ prefix: '', final: 'J' }, (params) => {
        if (params[0] === 3) return true; // Block clearing scrollback
        return false;
      });

      term.parser.registerCsiHandler({ prefix: '?', final: 'h' }, (params) => {
        if (preventAltScreenRef.current && (params[0] === 1049 || params[0] === 47 || params[0] === 1047)) {
          return true;
        }
        return false;
      });
    } catch (e) {}

    try {
      term.attachCustomWheelEventHandler((e) => {
        if (e.ctrlKey) return true;
        if (preventAltScreenRef.current || e.shiftKey) {
          const multiplier = e.shiftKey ? 16 : 4;
          term.scrollLines(e.deltaY > 0 ? multiplier : -multiplier);
          return false;
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

    setTimeout(() => {
      try {
        fitAddon.fit();
        if (connection && session && term.rows && term.cols) {
          connection.resizeTerminal(session.session_id, term.rows, term.cols);
        }
      } catch (e) {}
    }, 20);

    xtermRef.current = term;
    fitAddonRef.current = fitAddon;

    const onDataDisposable = term.onData((data) => {
      if (connection && session) {
        const b64 = stringToBase64(data);
        connection.sendInput(session.session_id, b64);
      }
    });

    const handleFit = () => {
      try {
        fitAddon.fit();
        if (connection && session && term.rows && term.cols) {
          connection.resizeTerminal(session.session_id, term.rows, term.cols);
        }
      } catch (e) {}
    };

    const resizeObserver = new ResizeObserver(() => {
      requestAnimationFrame(handleFit);
    });
    resizeObserver.observe(containerRef.current);

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
      setIsInitialReady(true);
    };

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

    // Listen only to this session's terminal output
    const unsubOutput = connection.on('terminal_output', (msg) => {
      if (msg.session_id === session.session_id && msg.data) {
        try {
          const text = base64ToString(msg.data);
          writeChunk(text);
        } catch (e) {}
      }
    });

    const unsubAttached = connection.on('session_attached', (msg) => {
      if (msg.session.session_id === session.session_id) {
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
          if (batch) writeChunk(batch);
        }
      }
    });

    // Attach to session
    connection.attachSession(session.session_id, 0, syncFullHistoryRef.current);

    return () => {
      if (scrollRafId) cancelAnimationFrame(scrollRafId);
      scrollDisposable?.dispose?.();
      clearTimeout(replayTimer);
      onDataDisposable.dispose();
      resizeObserver.disconnect();
      unsubOutput();
      unsubAttached();
      term.dispose();
    };
  }, [session?.session_id]);

  // Compute magnetic drop zones on drag over
  const handleDragOver = (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (!tileRootRef.current) return;
    const rect = tileRootRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const w = rect.width;
    const h = rect.height;

    const thresholdX = w * 0.22;
    const thresholdY = h * 0.22;

    let zone = 'center';
    if (x < thresholdX) zone = 'left';
    else if (x > w - thresholdX) zone = 'right';
    else if (y < thresholdY) zone = 'top';
    else if (y > h - thresholdY) zone = 'bottom';

    onDragOverTile?.(e, session.session_id, zone);
  };

  const isCurrentDragTarget = dragState?.targetId === session.session_id;
  const isBeingDragged = dragState?.draggedId === session.session_id;

  return (
    <div
      ref={tileRootRef}
      onClick={() => onFocus?.(session.session_id)}
      onDragOver={handleDragOver}
      onDragLeave={(e) => onDragLeaveTile?.(e, session.session_id)}
      onDrop={(e) => onDropOnTile?.(e, session.session_id)}
      className={`relative flex flex-col flex-1 min-w-[280px] min-h-[220px] rounded-2xl overflow-hidden transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] select-none ${
        isBeingDragged
          ? 'opacity-40 scale-[0.98] ring-2 ring-cyan-500/40'
          : isFocused
          ? 'ring-2 ring-cyan-400/90 shadow-2xl shadow-cyan-950/50 bg-[#090d16]'
          : 'ring-1 ring-slate-800/80 hover:ring-slate-700/80 bg-[#070b14]'
      }`}
    >
      {/* Tile Header Bar */}
      <div
        className={`flex items-center justify-between px-3 py-1.5 border-b text-[11px] font-mono transition-colors select-none ${
          isFocused
            ? 'bg-gradient-to-r from-slate-900 via-[#0c162c] to-slate-900 border-cyan-500/40 text-slate-200'
            : 'bg-[#060a12] border-slate-800/80 text-slate-400 hover:text-slate-300'
        }`}
      >
        {/* Left: Drag Handle, Slot Index, Title */}
        <div className="flex items-center gap-2 truncate">
          <div
            draggable
            onDragStart={(e) => onDragStart?.(e, session.session_id)}
            title="点击并拖拽调换终端位置；拖至边缘可自动吸附拼接"
            className="cursor-grab active:cursor-grabbing p-0.5 -ml-1 rounded text-slate-500 hover:text-cyan-400 hover:bg-slate-800 transition-colors"
          >
            <GripVertical className="w-3.5 h-3.5" />
          </div>

          <span
            className={`px-1.5 py-0.2 rounded text-[9px] font-bold tracking-tight ${
              isFocused
                ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/50'
                : 'bg-slate-800/80 text-slate-400 border border-slate-700/50'
            }`}
          >
            #{index + 1}
          </span>

          <span className="flex items-center gap-1.5 truncate">
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                session.status === 'running' || !session.status ? 'bg-emerald-400 animate-pulse' : 'bg-slate-500'
              }`}
            />
            <span className={`font-semibold truncate ${isFocused ? 'text-cyan-300' : 'text-slate-300'}`}>
              {session.name || 'Terminal'}
            </span>
            <span className="text-[10px] text-slate-500 hidden sm:inline">({session.pid || '-'})</span>
          </span>
        </div>

        {/* Right: Quick actions */}
        <div className="flex items-center gap-1 shrink-0">
          {/* Truncated history on-demand button */}
          {isHistoryTruncated && !syncFullHistory && (
            <button
              onClick={handleLoadAllHistoryNow}
              title="当前终端默认截断历史保障秒开。点击一次性拉取并渲染完整历史"
              className="px-2 py-0.5 rounded text-[9px] font-medium border border-amber-500/50 bg-amber-500/20 text-amber-300 hover:bg-amber-500/30 hover:border-amber-400 flex items-center gap-1 transition-all cursor-pointer"
            >
              <Download className="w-2.5 h-2.5 text-amber-400" />
              <span className="hidden xl:inline">全部历史</span>
            </button>
          )}

          {/* Full history sync toggle */}
          <button
            onClick={handleToggleSyncFullHistory}
            title={syncFullHistory ? '已开启完整历史同步' : '默认截断历史（极速秒开）'}
            className={`p-1 rounded text-[10px] border transition-all cursor-pointer ${
              syncFullHistory
                ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/60'
                : 'bg-slate-800/60 text-slate-400 border-slate-700/60 hover:text-slate-200'
            }`}
          >
            <History className="w-3 h-3" />
          </button>

          {/* Anti-Truncate Alt Screen lock toggle */}
          <button
            onClick={handleToggleAltScreen}
            title={preventAltScreen ? '已锁定主屏缓冲区 (Codex 60fps 高速滚动)' : '未锁定备用屏'}
            className={`p-1 rounded text-[10px] border transition-all cursor-pointer ${
              preventAltScreen
                ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/60'
                : 'bg-slate-800/60 text-slate-400 border-slate-700/60 hover:text-slate-200'
            }`}
          >
            <Sparkles className="w-3 h-3" />
          </button>

          {/* Split Right */}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onSplitRight?.(session.session_id);
            }}
            title="向右分屏拼接 (Alt + \)"
            className="p-1 rounded text-slate-400 hover:text-cyan-300 hover:bg-slate-800 transition-colors cursor-pointer"
          >
            <Columns2 className="w-3 h-3" />
          </button>

          {/* Split Down */}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onSplitDown?.(session.session_id);
            }}
            title="向下分屏拼接 (Alt + -)"
            className="p-1 rounded text-slate-400 hover:text-cyan-300 hover:bg-slate-800 transition-colors cursor-pointer"
          >
            <Rows2 className="w-3 h-3" />
          </button>

          {/* Maximize / Restore */}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onToggleMaximize?.(session.session_id);
            }}
            title={isMaximized ? '退出单屏聚焦，还原终端拼图 (Alt + M)' : '全屏聚焦当前终端 (Alt + M)'}
            className={`p-1 rounded transition-colors cursor-pointer ${
              isMaximized
                ? 'bg-cyan-500/20 text-cyan-300 hover:bg-cyan-500/30'
                : 'text-slate-400 hover:text-cyan-300 hover:bg-slate-800'
            }`}
          >
            {isMaximized ? <Minimize2 className="w-3 h-3" /> : <Maximize2 className="w-3 h-3" />}
          </button>

          {/* Close Tile (only if multiple tiles exist) */}
          {totalTiles > 1 && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onClose?.(session.session_id);
              }}
              title="从终端拼图中移出此分屏 (Alt + W)"
              className="p-1 rounded text-slate-400 hover:text-rose-400 hover:bg-rose-950/40 transition-colors cursor-pointer"
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      {/* Terminal Viewport */}
      <div className="flex-1 relative p-1 overflow-hidden bg-[#090d16]">
        {/* Loading Overlay */}
        {!isInitialReady && (
          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center bg-[#090d16]/90 backdrop-blur-sm pointer-events-none select-none transition-opacity duration-200">
            <div className="w-8 h-8 rounded-full border-2 border-cyan-500/20 border-t-cyan-400 animate-spin mb-2" />
            <span className="text-[11px] font-mono text-slate-400">同步终端历史中...</span>
          </div>
        )}

        {/* Floating Mini Scroll HUD */}
        {linesScrolledUp > 0 && (
          <div className="absolute right-3 top-3 z-20 flex flex-col items-center bg-[#070d18]/90 backdrop-blur-md border border-slate-700/80 rounded-lg p-0.5 shadow-xl">
            <button
              onClick={handleScrollToTop}
              title="直达顶部"
              className="w-5 h-5 flex items-center justify-center rounded text-slate-400 hover:text-cyan-300 hover:bg-slate-800/80"
            >
              <ChevronsUp className="w-3 h-3" />
            </button>
            <div
              onClick={handleScrollToBottom}
              title="点击回到底部"
              className="my-0.5 px-1 rounded text-[8px] font-mono font-bold bg-cyan-950 text-cyan-300 border border-cyan-500/40 cursor-pointer"
            >
              +{linesScrolledUp > 999 ? `${(linesScrolledUp / 1000).toFixed(1)}k` : linesScrolledUp}
            </div>
            <button
              onClick={handleScrollToBottom}
              title="直达最新输出"
              className="w-5 h-5 flex items-center justify-center rounded text-cyan-400 hover:text-cyan-200 hover:bg-cyan-950/60 animate-pulse"
            >
              <ChevronsDown className="w-3 h-3" />
            </button>
          </div>
        )}

        {/* XTerm Root Element */}
        <div ref={containerRef} className="w-full h-full" />
      </div>

      {/* Magnetic Snapping & Drag Drop Guide Overlays */}
      {isCurrentDragTarget && !isBeingDragged && (
        <div className="absolute inset-0 z-30 pointer-events-none">
          {dragState.zone === 'center' && (
            <div className="absolute inset-2 rounded-xl bg-indigo-500/20 border-2 border-dashed border-indigo-400 flex flex-col items-center justify-center backdrop-blur-xs animate-pulse">
              <ArrowRightLeft className="w-8 h-8 text-indigo-300 mb-1" />
              <span className="text-xs font-mono font-bold text-indigo-200 tracking-wider">
                ⇄ 调换拼图位置 (Swap)
              </span>
            </div>
          )}

          {dragState.zone === 'left' && (
            <div className="absolute top-2 bottom-2 left-2 w-[48%] rounded-xl bg-cyan-500/25 border-2 border-dashed border-cyan-400 flex flex-col items-center justify-center backdrop-blur-xs animate-pulse">
              <Columns2 className="w-8 h-8 text-cyan-300 mb-1" />
              <span className="text-xs font-mono font-bold text-cyan-200 tracking-wider">
                ◀ 磁吸拼接到左侧
              </span>
            </div>
          )}

          {dragState.zone === 'right' && (
            <div className="absolute top-2 bottom-2 right-2 w-[48%] rounded-xl bg-cyan-500/25 border-2 border-dashed border-cyan-400 flex flex-col items-center justify-center backdrop-blur-xs animate-pulse">
              <Columns2 className="w-8 h-8 text-cyan-300 mb-1" />
              <span className="text-xs font-mono font-bold text-cyan-200 tracking-wider">
                ▶ 磁吸拼接到右侧
              </span>
            </div>
          )}

          {dragState.zone === 'top' && (
            <div className="absolute top-2 left-2 right-2 h-[48%] rounded-xl bg-cyan-500/25 border-2 border-dashed border-cyan-400 flex flex-col items-center justify-center backdrop-blur-xs animate-pulse">
              <Rows2 className="w-8 h-8 text-cyan-300 mb-1" />
              <span className="text-xs font-mono font-bold text-cyan-200 tracking-wider">
                ▲ 磁吸拼接到上方
              </span>
            </div>
          )}

          {dragState.zone === 'bottom' && (
            <div className="absolute bottom-2 left-2 right-2 h-[48%] rounded-xl bg-cyan-500/25 border-2 border-dashed border-cyan-400 flex flex-col items-center justify-center backdrop-blur-xs animate-pulse">
              <Rows2 className="w-8 h-8 text-cyan-300 mb-1" />
              <span className="text-xs font-mono font-bold text-cyan-200 tracking-wider">
                ▼ 磁吸拼接到下方
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
