import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  Columns2,
  Rows2,
  Grid2X2,
  Maximize2,
  Minimize2,
  Plus,
  Keyboard,
  Sparkles,
  Layers,
  ArrowRightLeft,
  X,
  Split,
  ChevronDown,
  Terminal as TerminalIcon,
  RefreshCw,
} from 'lucide-react';
import MosaicTerminalTile from './MosaicTerminalTile';
import MobileToolbar from './MobileToolbar';

export default function MosaicTerminalGrid({
  sessions,
  activeSessionId,
  onSelectSession,
  connection,
  connectionState,
  onSwitchToDashboard,
  onNewTerminal,
  onTerminateSession,
  onRestartSession,
  onDeleteSession,
}) {
  // Pinned session IDs in the mosaic grid
  const [pinnedIds, setPinnedIds] = useState(() => {
    if (activeSessionId) return [activeSessionId];
    if (sessions.length > 0) return [sessions[0].session_id];
    return [];
  });

  // Current active focused tile
  const [focusedId, setFocusedId] = useState(activeSessionId || (sessions[0]?.session_id ?? null));

  // Maximize specific tile (Alt + M)
  const [maximizedId, setMaximizedId] = useState(null);

  // Layout mode: 'auto' | '1x1' | '1x2' | '2x1' | '2x2' | '1+2'
  const [layoutMode, setLayoutMode] = useState('auto');

  // Drag and drop state
  const [dragState, setDragState] = useState({
    draggedId: null,
    targetId: null,
    zone: null, // 'center' | 'left' | 'right' | 'top' | 'bottom'
  });

  // Shortcut cheat sheet modal
  const [isShortcutModalOpen, setIsShortcutModalOpen] = useState(false);

  // Session selector popover
  const [isAddPickerOpen, setIsAddPickerOpen] = useState(false);

  // Sync activeSessionId with pinnedIds
  useEffect(() => {
    if (activeSessionId) {
      setFocusedId(activeSessionId);
      setPinnedIds((prev) => {
        if (!prev.includes(activeSessionId)) {
          // If in single mode or empty, replace or add
          if (prev.length <= 1) return [activeSessionId];
          return [...prev, activeSessionId];
        }
        return prev;
      });
    }
  }, [activeSessionId]);

  // Clean up pinnedIds if sessions were deleted
  useEffect(() => {
    const existingIds = new Set(sessions.map((s) => s.session_id));
    setPinnedIds((prev) => {
      const valid = prev.filter((id) => existingIds.has(id));
      if (valid.length === 0 && sessions.length > 0) {
        return [sessions[0].session_id];
      }
      return valid;
    });
    if (focusedId && !existingIds.has(focusedId)) {
      setFocusedId(sessions[0]?.session_id ?? null);
    }
    if (maximizedId && !existingIds.has(maximizedId)) {
      setMaximizedId(null);
    }
  }, [sessions]);

  // Resolve session objects for pinned IDs
  const pinnedSessions = pinnedIds
    .map((id) => sessions.find((s) => s.session_id === id))
    .filter(Boolean);

  // Focus a specific tile
  const handleFocusTile = useCallback((id) => {
    setFocusedId(id);
    onSelectSession?.(id);
  }, [onSelectSession]);

  // Add session to mosaic
  const handleAddSessionToMosaic = (id) => {
    setIsAddPickerOpen(false);
    if (!pinnedIds.includes(id)) {
      const next = [...pinnedIds, id];
      setPinnedIds(next);
      handleFocusTile(id);
    } else {
      handleFocusTile(id);
    }
  };

  // Remove tile from mosaic
  const handleRemoveTile = (id) => {
    setPinnedIds((prev) => {
      if (prev.length <= 1) return prev;
      const next = prev.filter((item) => item !== id);
      if (focusedId === id) {
        const nextFocus = next[0];
        setFocusedId(nextFocus);
        onSelectSession?.(nextFocus);
      }
      return next;
    });
    if (maximizedId === id) setMaximizedId(null);
  };

  // Split right (add unpinned session or duplicate focus)
  const handleSplitRight = (fromId) => {
    const unpinned = sessions.find((s) => !pinnedIds.includes(s.session_id));
    if (unpinned) {
      handleAddSessionToMosaic(unpinned.session_id);
    } else {
      // Launch new terminal and pin it
      onNewTerminal?.();
    }
  };

  // Split down
  const handleSplitDown = (fromId) => {
    handleSplitRight(fromId);
  };

  // Toggle Maximize / Zoom
  const handleToggleMaximize = (id) => {
    setMaximizedId((prev) => (prev === id ? null : id));
  };

  // Drag & drop handlers
  const handleDragStart = (e, id) => {
    e.dataTransfer.setData('text/plain', id);
    e.dataTransfer.effectAllowed = 'move';
    setDragState({ draggedId: id, targetId: null, zone: null });
  };

  const handleDragOverTile = (e, targetId, zone) => {
    if (dragState.draggedId && dragState.draggedId !== targetId) {
      setDragState({ draggedId: dragState.draggedId, targetId, zone });
    }
  };

  const handleDragLeaveTile = (e, targetId) => {
    if (dragState.targetId === targetId) {
      setDragState((prev) => ({ ...prev, targetId: null, zone: null }));
    }
  };

  const handleDropOnTile = (e, targetId) => {
    e.preventDefault();
    const draggedId = dragState.draggedId;
    const zone = dragState.zone;
    setDragState({ draggedId: null, targetId: null, zone: null });

    if (!draggedId || draggedId === targetId) return;

    setPinnedIds((prev) => {
      const fromIndex = prev.indexOf(draggedId);
      const toIndex = prev.indexOf(targetId);
      if (fromIndex === -1 || toIndex === -1) return prev;

      const next = [...prev];
      if (zone === 'center' || !zone) {
        // Direct swap positions
        next[fromIndex] = targetId;
        next[toIndex] = draggedId;
      } else if (zone === 'left' || zone === 'top') {
        // Insert before target
        next.splice(fromIndex, 1);
        const newTargetIdx = next.indexOf(targetId);
        next.splice(newTargetIdx, 0, draggedId);
      } else if (zone === 'right' || zone === 'bottom') {
        // Insert after target
        next.splice(fromIndex, 1);
        const newTargetIdx = next.indexOf(targetId);
        next.splice(newTargetIdx + 1, 0, draggedId);
      }
      return next;
    });

    handleFocusTile(draggedId);
  };

  // Rich Keyboard Navigation & Hotkeys
  useEffect(() => {
    const handleKeyDown = (e) => {
      // Don't intercept if modifier is not Alt (unless Esc / F11)
      if (!e.altKey && e.key !== 'F11' && e.key !== 'Escape') return;

      if (e.key === 'F11' || (e.altKey && (e.key === 'm' || e.key === 'M'))) {
        e.preventDefault();
        if (focusedId) handleToggleMaximize(focusedId);
        return;
      }

      if (e.altKey && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        setIsShortcutModalOpen((prev) => !prev);
        return;
      }

      if (e.key === 'Escape' && isShortcutModalOpen) {
        setIsShortcutModalOpen(false);
        return;
      }

      // Alt + 1..9: Focus slot by number
      if (e.altKey && e.key >= '1' && e.key <= '9') {
        const slot = parseInt(e.key, 10) - 1;
        if (slot < pinnedIds.length) {
          e.preventDefault();
          handleFocusTile(pinnedIds[slot]);
          return;
        }
      }

      // Alt + [ / Alt + ]: Cycle previous / next tile
      if (e.altKey && (e.key === '[' || e.key === ']')) {
        e.preventDefault();
        const currIdx = pinnedIds.indexOf(focusedId);
        if (currIdx !== -1 && pinnedIds.length > 1) {
          const delta = e.key === ']' ? 1 : -1;
          const nextIdx = (currIdx + delta + pinnedIds.length) % pinnedIds.length;
          handleFocusTile(pinnedIds[nextIdx]);
        }
        return;
      }

      // Alt + Arrow keys: Spatial 2D directional focus navigation
      if (e.altKey && ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
        e.preventDefault();
        const currIdx = pinnedIds.indexOf(focusedId);
        if (currIdx === -1 || pinnedIds.length <= 1) return;

        let nextIdx = currIdx;
        const total = pinnedIds.length;

        if (total === 2) {
          nextIdx = currIdx === 0 ? 1 : 0;
        } else if (total === 4) {
          // 2x2 grid layout: 0 1
          //                  2 3
          if (e.key === 'ArrowRight') nextIdx = currIdx % 2 === 0 ? currIdx + 1 : currIdx;
          else if (e.key === 'ArrowLeft') nextIdx = currIdx % 2 === 1 ? currIdx - 1 : currIdx;
          else if (e.key === 'ArrowDown') nextIdx = currIdx < 2 ? currIdx + 2 : currIdx;
          else if (e.key === 'ArrowUp') nextIdx = currIdx >= 2 ? currIdx - 2 : currIdx;
        } else {
          // General wrap
          if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
            nextIdx = (currIdx + 1) % total;
          } else {
            nextIdx = (currIdx - 1 + total) % total;
          }
        }

        if (nextIdx !== currIdx) {
          handleFocusTile(pinnedIds[nextIdx]);
        }
        return;
      }

      // Alt + \: Split Right
      if (e.altKey && e.key === '\\') {
        e.preventDefault();
        handleSplitRight(focusedId);
        return;
      }

      // Alt + -: Split Down
      if (e.altKey && e.key === '-') {
        e.preventDefault();
        handleSplitDown(focusedId);
        return;
      }

      // Alt + w: Close tile from mosaic
      if (e.altKey && (e.key === 'w' || e.key === 'W')) {
        if (pinnedIds.length > 1 && focusedId) {
          e.preventDefault();
          handleRemoveTile(focusedId);
          return;
        }
      }

      // Alt + s: Swap with next tile
      if (e.altKey && (e.key === 's' || e.key === 'S')) {
        if (pinnedIds.length > 1 && focusedId) {
          e.preventDefault();
          const currIdx = pinnedIds.indexOf(focusedId);
          const nextIdx = (currIdx + 1) % pinnedIds.length;
          setPinnedIds((prev) => {
            const next = [...prev];
            const temp = next[currIdx];
            next[currIdx] = next[nextIdx];
            next[nextIdx] = temp;
            return next;
          });
          return;
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [focusedId, pinnedIds, isShortcutModalOpen, handleFocusTile]);

  // Compute dynamic grid layout CSS based on count and layoutMode
  const getGridClasses = () => {
    if (maximizedId) return 'grid grid-cols-1 grid-rows-1';

    const count = pinnedSessions.length;
    if (count <= 1) return 'grid grid-cols-1 grid-rows-1';

    if (layoutMode === 'split-h') return 'grid grid-cols-1 md:grid-cols-2 grid-rows-1';
    if (layoutMode === 'split-v') return 'grid grid-cols-1 grid-rows-2';
    if (layoutMode === 'grid-4') return 'grid grid-cols-1 sm:grid-cols-2 grid-rows-2';

    // Auto smart mosaic:
    if (count === 2) return 'grid grid-cols-1 md:grid-cols-2 grid-rows-1';
    if (count === 3) return 'grid grid-cols-1 md:grid-cols-3 grid-rows-1';
    if (count === 4) return 'grid grid-cols-1 sm:grid-cols-2 grid-rows-2';
    if (count > 4) return 'grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 auto-rows-fr';
    return 'grid grid-cols-1 md:grid-cols-2';
  };

  const unpinnedSessions = sessions.filter((s) => !pinnedIds.includes(s.session_id));

  if (sessions.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-6 text-center bg-[#090d16]">
        <div className="w-14 h-14 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-center text-slate-500 mb-4 shadow-xl">
          <TerminalIcon className="w-7 h-7 text-cyan-400" />
        </div>
        <h3 className="font-semibold text-slate-200">当前没有运行中的终端会话</h3>
        <p className="text-xs text-slate-500 max-w-sm mt-1 mb-5">
          点击下方按钮一键拉起新终端，体验支持自由拖拽调换位置、边缘自动吸附分屏与丰富快捷键的终端拼图系统。
        </p>
        <button
          onClick={() => onNewTerminal?.()}
          className="px-4 py-2 rounded-xl bg-gradient-to-r from-cyan-600 to-indigo-600 hover:from-cyan-500 hover:to-indigo-500 text-white font-medium text-xs shadow-lg shadow-cyan-950/50 active:scale-95 transition-all cursor-pointer"
        >
          + 新建终端会话
        </button>
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col relative overflow-hidden bg-[#090d16]">
      {/* Top Mosaic Navigation & Control Bar */}
      <div className="flex items-center justify-between px-3 py-1.5 bg-[#060a12] border-b border-slate-800/80 text-xs font-mono z-20 shrink-0 select-none">
        {/* Left: Active Focus Badge & Mosaic Layout Switcher */}
        <div className="flex items-center gap-2 sm:gap-3">
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-lg bg-cyan-950/40 border border-cyan-500/30 text-cyan-300 text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-pulse" />
            <span className="hidden sm:inline">终端拼图:</span>
            <span className="font-bold">{pinnedSessions.length} 个分屏</span>
            {maximizedId && (
              <span className="ml-1 px-1.5 py-0.2 rounded bg-amber-500/20 text-amber-300 text-[9px] border border-amber-500/40">
                已放大聚焦
              </span>
            )}
          </div>

          {/* Quick Layout Presets */}
          <div className="hidden md:flex items-center p-0.5 rounded-lg bg-slate-900/90 border border-slate-800 text-[10px]">
            <button
              onClick={() => {
                setLayoutMode('split-h');
                setMaximizedId(null);
              }}
              title="左右双分屏 (1x2)"
              className={`p-1 rounded transition-colors ${
                layoutMode === 'split-h' ? 'bg-cyan-500/20 text-cyan-300' : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Columns2 className="w-3.5 h-3.5" />
            </button>
            <button
              onClick={() => {
                setLayoutMode('split-v');
                setMaximizedId(null);
              }}
              title="上下双分屏 (2x1)"
              className={`p-1 rounded transition-colors ${
                layoutMode === 'split-v' ? 'bg-cyan-500/20 text-cyan-300' : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Rows2 className="w-3.5 h-3.5" />
            </button>
            <button
              onClick={() => {
                setLayoutMode('grid-4');
                setMaximizedId(null);
              }}
              title="四宫格拼图 (2x2)"
              className={`p-1 rounded transition-colors ${
                layoutMode === 'grid-4' ? 'bg-cyan-500/20 text-cyan-300' : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Grid2X2 className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* Right: Stitch Session Button, Shortcut Helper */}
        <div className="flex items-center gap-1.5 sm:gap-2 relative">
          {/* Add / Stitch Session Dropdown */}
          <div className="relative">
            <button
              onClick={() => setIsAddPickerOpen((prev) => !prev)}
              title="将其他运行中的会话拼接吸附到当前终端拼图"
              className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 hover:text-white border border-slate-700 text-[11px] font-medium transition-all active:scale-95 cursor-pointer shadow-sm"
            >
              <Plus className="w-3.5 h-3.5 text-cyan-400" />
              <span className="hidden sm:inline">拼接终端</span>
              <ChevronDown className="w-3 h-3 text-slate-400" />
            </button>

            {/* Dropdown Menu */}
            {isAddPickerOpen && (
              <div className="absolute right-0 top-full mt-1.5 w-60 rounded-xl bg-[#0c1220] border border-slate-700 shadow-2xl p-1.5 z-50 animate-in fade-in zoom-in-95 duration-150">
                <div className="px-2 py-1 text-[10px] font-bold text-slate-400 uppercase tracking-wider border-b border-slate-800">
                  选择会话拼接进拼图
                </div>

                <div className="max-h-52 overflow-y-auto my-1 space-y-0.5">
                  {unpinnedSessions.length > 0 ? (
                    unpinnedSessions.map((s) => (
                      <button
                        key={s.session_id}
                        onClick={() => handleAddSessionToMosaic(s.session_id)}
                        className="w-full flex items-center justify-between px-2.5 py-1.5 rounded-lg text-left text-xs text-slate-200 hover:bg-cyan-500/20 hover:text-cyan-300 transition-colors cursor-pointer"
                      >
                        <div className="flex items-center gap-2 truncate">
                          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                          <span className="truncate">{s.name}</span>
                        </div>
                        <span className="text-[10px] font-mono text-slate-500">{s.pid || '-'}</span>
                      </button>
                    ))
                  ) : (
                    <div className="px-2.5 py-2 text-[11px] text-slate-500 text-center">
                      所有会话均已在拼图中
                    </div>
                  )}
                </div>

                <button
                  onClick={() => {
                    setIsAddPickerOpen(false);
                    onNewTerminal?.();
                  }}
                  className="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-lg bg-cyan-600/30 hover:bg-cyan-600/50 text-cyan-300 border border-cyan-500/30 text-xs font-medium transition-colors cursor-pointer"
                >
                  <Plus className="w-3 h-3" />
                  <span>新建并加入拼图</span>
                </button>
              </div>
            )}
          </div>

          {/* Keyboard Shortcut Cheat Sheet Button */}
          <button
            onClick={() => setIsShortcutModalOpen(true)}
            title="查看终端拼图快捷键 (Alt + K)"
            className="flex items-center gap-1 p-1 sm:px-2 sm:py-1 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-cyan-300 border border-slate-800 transition-colors cursor-pointer"
          >
            <Keyboard className="w-3.5 h-3.5" />
            <span className="hidden md:inline text-[11px]">快捷键 (Alt+K)</span>
          </button>
        </div>
      </div>

      {/* Main Mosaic Grid Container */}
      <div
        className={`flex-1 p-2 gap-2 overflow-hidden bg-[#090d16] ${getGridClasses()}`}
        style={{
          transition: 'all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1)',
        }}
      >
        {pinnedSessions.map((session, idx) => {
          // If a tile is maximized, only render the maximized one
          if (maximizedId && session.session_id !== maximizedId) return null;

          return (
            <MosaicTerminalTile
              key={session.session_id}
              session={session}
              connection={connection}
              isFocused={session.session_id === focusedId}
              onFocus={handleFocusTile}
              isMaximized={maximizedId === session.session_id}
              onToggleMaximize={handleToggleMaximize}
              onSplitRight={handleSplitRight}
              onSplitDown={handleSplitDown}
              onClose={handleRemoveTile}
              index={idx}
              totalTiles={pinnedSessions.length}
              onDragStart={handleDragStart}
              onDragOverTile={handleDragOverTile}
              onDragLeaveTile={handleDragLeaveTile}
              onDropOnTile={handleDropOnTile}
              dragState={dragState}
            />
          );
        })}
      </div>

      {/* Floating Keyboard Shortcuts Cheat Sheet Modal */}
      {isShortcutModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm animate-in fade-in duration-150 p-4">
          <div className="w-full max-w-md rounded-2xl bg-[#0b101d] border border-cyan-500/40 shadow-2xl p-5 text-slate-200 select-none">
            <div className="flex items-center justify-between pb-3 border-b border-slate-800">
              <div className="flex items-center gap-2">
                <Sparkles className="w-5 h-5 text-cyan-400" />
                <h3 className="font-bold text-sm text-white font-mono">终端拼图快捷键指南</h3>
              </div>
              <button
                onClick={() => setIsShortcutModalOpen(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="py-3 space-y-2 text-xs font-mono">
              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">切换焦点到对应拼图</span>
                <span className="px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/40 font-bold">
                  Alt + 1 ~ 4
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">方向键空间导航切换焦点</span>
                <span className="px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/40 font-bold">
                  Alt + ↑ / ↓ / ← / →
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">轮转切换上一个/下一个拼图</span>
                <span className="px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/40 font-bold">
                  Alt + [ / Alt + ]
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">聚焦放大当前终端 / 还原拼图</span>
                <span className="px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/40 font-bold">
                  Alt + M / F11
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">向右侧拼接分屏</span>
                <span className="px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/40 font-bold">
                  Alt + \
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">向下方拼接分屏</span>
                <span className="px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/40 font-bold">
                  Alt + -
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">与相邻分屏调换位置 (Swap)</span>
                <span className="px-2 py-0.5 rounded bg-cyan-950 text-cyan-300 border border-cyan-500/40 font-bold">
                  Alt + S
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">移出当前拼图分屏</span>
                <span className="px-2 py-0.5 rounded bg-rose-950/60 text-rose-300 border border-rose-500/40 font-bold">
                  Alt + W
                </span>
              </div>

              <div className="flex items-center justify-between py-1 px-2 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-slate-400">鼠标自由拖拽 & 边缘磁吸</span>
                <span className="px-2 py-0.5 rounded bg-indigo-950 text-indigo-300 border border-indigo-500/40 font-bold">
                  拖拽头部 ⋮⋮
                </span>
              </div>
            </div>

            <div className="pt-3 border-t border-slate-800 flex justify-end">
              <button
                onClick={() => setIsShortcutModalOpen(false)}
                className="px-4 py-1.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-medium text-xs shadow-md shadow-cyan-900/30 transition-all cursor-pointer"
              >
                我知道了 (Esc)
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
