import React from 'react';
import { 
  Terminal, 
  TerminalSquare, 
  Bot, 
  X, 
  Plus, 
  ArrowLeft, 
  Play, 
  Square, 
  Trash2 
} from 'lucide-react';

export default function SessionTabs({
  sessions,
  activeSessionId,
  onSelectSession,
  onCloseSession,
  onNewSession,
  onSwitchToDashboard,
  onRestartSession,
  onTerminateSession,
  onDeleteSession,
}) {
  const activeSession = sessions?.find((s) => s.session_id === activeSessionId) || sessions?.[0];

  return (
    <div className="h-10 bg-[#0c1220] border-b border-[#1e293b] flex items-center justify-between px-2 gap-2 shrink-0 select-none z-20">
      {/* Left: Back to Control Panel Button */}
      <div className="flex items-center gap-1 shrink-0">
        <button
          onClick={onSwitchToDashboard}
          className="flex items-center gap-1 px-2 sm:px-3 py-1 rounded-lg bg-cyan-600/20 hover:bg-cyan-600/30 text-cyan-300 border border-cyan-500/40 text-xs font-semibold transition-all shadow-sm cursor-pointer"
          title="返回控制面板"
        >
          <ArrowLeft className="w-3.5 h-3.5 text-cyan-400 shrink-0" />
          <span className="hidden sm:inline">返回控制面板</span>
          <span className="inline sm:hidden text-[11px]">看板</span>
        </button>
        <span className="h-4 w-px bg-slate-800 mx-1 hidden sm:block" />
      </div>

      {/* Center: Terminal Tabs */}
      <div className="flex-1 flex items-center gap-1 overflow-x-auto no-scrollbar min-w-0">
        {sessions?.map((session) => {
          const isActive = session.session_id === activeSessionId;
          const isWaiting = session.status === 'waiting_input';
          const isStopped = session.status === 'stopped';
          const isTerminal = session.session_type === 'terminal';

          return (
            <div
              key={session.session_id}
              onClick={() => onSelectSession(session.session_id)}
              className={`group relative flex items-center gap-1.5 px-2 sm:px-3 py-1.5 rounded-t-lg text-xs font-mono cursor-pointer transition-all duration-150 border-t border-x shrink-0 ${
                isActive
                  ? 'bg-[#0f172a] text-cyan-300 border-slate-700/80 shadow-inner'
                  : 'bg-[#0b101d] text-slate-400 hover:text-slate-200 border-transparent hover:bg-slate-900/60'
              }`}
            >
              {/* Status Indicator */}
              <span
                className={`w-1.5 h-1.5 sm:w-2 sm:h-2 rounded-full shrink-0 ${
                  isWaiting
                    ? 'bg-amber-400 animate-ping'
                    : isStopped
                    ? 'bg-rose-500'
                    : 'bg-emerald-400 animate-pulse'
                }`}
                title={isStopped ? '已退出' : isWaiting ? '等待输入' : '运行中'}
              />

              {isTerminal ? (
                <TerminalSquare className="w-3 h-3 text-cyan-400/80 shrink-0" />
              ) : (
                <Bot className="w-3 h-3 text-purple-400/80 shrink-0" />
              )}

              <span className="truncate max-w-[70px] sm:max-w-[130px] font-medium text-[11px] sm:text-xs">
                {session.name}
              </span>

              {/* Tab Close X button */}
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onCloseSession(session);
                }}
                title={isStopped ? '删除此会话' : '关闭并删除此终端'}
                className="p-0.5 rounded text-slate-500 hover:text-rose-400 hover:bg-slate-800 transition-colors ml-0.5"
              >
                <X className="w-3 h-3" />
              </button>

              {/* Active underline */}
              {isActive && (
                <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-gradient-to-r from-cyan-400 to-blue-500" />
              )}
            </div>
          );
        })}

        {/* New Terminal Shortcut Button */}
        <button
          onClick={onNewSession}
          title="开启新终端"
          className="p-1 rounded-md bg-slate-900/80 hover:bg-slate-800 text-slate-400 hover:text-cyan-400 border border-slate-800 transition-colors ml-0.5 shrink-0"
        >
          <Plus className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Right: Active Session Actions (Restart, Close, Delete) */}
      {activeSession && (
        <div className="flex items-center gap-1 sm:gap-1.5 shrink-0">
          {activeSession.status === 'stopped' ? (
            <>
              <button
                onClick={() => onRestartSession && onRestartSession(activeSession.session_id)}
                className="flex items-center gap-1 px-2 sm:px-2.5 py-1 rounded bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-300 border border-emerald-500/40 text-[11px] font-medium transition-all shadow-sm active:scale-95 cursor-pointer"
                title="在原目录原地重新开启全新终端"
              >
                <Play className="w-3 h-3 fill-emerald-300 shrink-0" />
                <span className="hidden sm:inline">重新开启</span>
                <span className="inline sm:hidden">重开</span>
              </button>
              <button
                onClick={() => {
                  if (window.confirm(`确定要彻底删除终端【${activeSession.name}】吗？`)) {
                    onDeleteSession && onDeleteSession(activeSession.session_id);
                  }
                }}
                className="flex items-center gap-1 px-2 sm:px-2.5 py-1 rounded bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 border border-rose-500/40 text-[11px] font-semibold transition-all shadow-sm active:scale-95 cursor-pointer"
                title="彻底删除此会话记录"
              >
                <Trash2 className="w-3 h-3 text-rose-400 shrink-0" />
                <span className="hidden sm:inline">删除终端</span>
                <span className="inline sm:hidden">删除</span>
              </button>
            </>
          ) : (
            <>
              <button
                onClick={() => onTerminateSession && onTerminateSession(activeSession.session_id)}
                className="flex items-center gap-1 px-2 sm:px-2.5 py-1 rounded bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 border border-amber-500/30 text-[11px] font-medium transition-colors cursor-pointer"
                title="关闭此终端进程"
              >
                <Square className="w-3 h-3 fill-amber-300 shrink-0" />
                <span className="hidden sm:inline">关闭终端</span>
                <span className="inline sm:hidden">关闭</span>
              </button>
              <button
                onClick={() => {
                  if (window.confirm(`终端【${activeSession.name}】正在运行中，确定要关闭并彻底删除吗？`)) {
                    onDeleteSession && onDeleteSession(activeSession.session_id);
                  }
                }}
                className="flex items-center gap-1 px-2 sm:px-2.5 py-1 rounded bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 border border-rose-500/40 text-[11px] font-semibold transition-all shadow-sm active:scale-95 cursor-pointer"
                title="关闭并彻底删除此终端"
              >
                <Trash2 className="w-3 h-3 text-rose-400 shrink-0" />
                <span className="hidden sm:inline">删除终端</span>
                <span className="inline sm:hidden">删除</span>
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
