import React, { useState } from 'react';
import { 
  Terminal, 
  Square, 
  FolderGit2, 
  TerminalSquare, 
  Bot, 
  Layers, 
  Edit3, 
  Check, 
  X,
  Play,
  Trash2,
  RefreshCw
} from 'lucide-react';

export default function RunningAgentsGrid({ 
  sessions, 
  onAttach, 
  onTerminate, 
  onRestart,
  onDelete,
  onUpdateFolder 
}) {
  const [editingSessionId, setEditingSessionId] = useState(null);
  const [editFolderName, setEditFolderName] = useState('');

  const formatUptime = (seconds) => {
    if (!seconds || seconds < 60) return `${seconds || 0}秒`;
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    if (mins < 60) return `${mins}分 ${secs}秒`;
    const hrs = Math.floor(mins / 60);
    return `${hrs}小时 ${mins % 60}分`;
  };

  const formatBytes = (bytes) => {
    if (!bytes) return '0 B';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const getStatusBadge = (status) => {
    switch (status) {
      case 'waiting_input':
        return (
          <span className="badge-waiting flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/50 text-[11px] font-medium font-mono">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-ping"></span>
            <span>等待输入</span>
          </span>
        );
      case 'running':
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 text-[11px] font-medium font-mono">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
            <span>运行中</span>
          </span>
        );
      case 'idle':
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-slate-800 text-slate-300 border border-slate-700 text-[11px] font-medium font-mono">
            <span className="w-1.5 h-1.5 rounded-full bg-slate-400"></span>
            <span>空闲</span>
          </span>
        );
      case 'stopped':
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-rose-500/10 text-rose-400 border border-rose-500/30 text-[11px] font-medium font-mono">
            <span className="w-1.5 h-1.5 rounded-full bg-rose-500"></span>
            <span>已退出</span>
          </span>
        );
      default:
        return (
          <span className="px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 text-[11px] font-mono">
            {status}
          </span>
        );
    }
  };

  const handleStartEdit = (session) => {
    setEditingSessionId(session.session_id);
    setEditFolderName(session.folder || '');
  };

  const handleSaveEdit = (sessionId) => {
    if (onUpdateFolder && editFolderName.trim()) {
      onUpdateFolder(sessionId, editFolderName.trim());
    }
    setEditingSessionId(null);
  };

  if (!sessions || sessions.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-800 bg-[#0c1220]/40 p-8 text-center flex flex-col items-center justify-center">
        <div className="w-12 h-12 rounded-full bg-slate-900 border border-slate-800 flex items-center justify-center text-slate-500 mb-3">
          <Terminal className="w-6 h-6" />
        </div>
        <h4 className="text-sm font-semibold text-slate-300">当前没有运行中的会话</h4>
        <p className="text-xs text-slate-500 mt-1 max-w-sm">
          点击上方“新建终端”或选择 Agent 卡片启动。会话独立运行在宿主后台，即使手机断网也绝不中断。
        </p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
      {sessions.map((session) => {
        const isTerminal = session.session_type === 'terminal';

        return (
          <div
            key={session.session_id}
            className={`rounded-xl border p-4 bg-[#0d1525]/90 transition-all ${
              session.status === 'waiting_input'
                ? 'border-amber-500/40 shadow-lg shadow-amber-950/20'
                : 'border-slate-800 hover:border-slate-700'
            }`}
          >
            {/* Header with Type & Status */}
            <div className="flex items-start justify-between gap-2">
              <div className="truncate">
                <div className="flex items-center gap-1.5">
                  {isTerminal ? (
                    <TerminalSquare className="w-4 h-4 text-cyan-400 shrink-0" />
                  ) : (
                    <Bot className="w-4 h-4 text-purple-400 shrink-0" />
                  )}
                  <h4 className="font-semibold text-sm text-slate-100 truncate">
                    {session.name}
                  </h4>
                </div>

                {/* Folder / Group badge */}
                <div className="mt-1 flex items-center gap-1 text-[11px] text-slate-400 font-mono">
                  {editingSessionId === session.session_id ? (
                    <div className="flex items-center gap-1 bg-slate-950 px-1.5 py-0.5 rounded border border-slate-700">
                      <input
                        type="text"
                        value={editFolderName}
                        onChange={(e) => setEditFolderName(e.target.value)}
                        className="bg-transparent text-white text-[11px] w-24 outline-none font-mono"
                        autoFocus
                      />
                      <button onClick={() => handleSaveEdit(session.session_id)} className="text-emerald-400">
                        <Check className="w-3 h-3" />
                      </button>
                      <button onClick={() => setEditingSessionId(null)} className="text-slate-500">
                        <X className="w-3 h-3" />
                      </button>
                    </div>
                  ) : (
                    <div
                      onClick={() => handleStartEdit(session)}
                      className="flex items-center gap-1 hover:text-cyan-300 cursor-pointer bg-slate-900/80 px-1.5 py-0.5 rounded border border-slate-800/80 text-[10px]"
                      title="点击修改所属分组/项目文件夹"
                    >
                      <Layers className="w-3 h-3 text-purple-400" />
                      <span className="truncate max-w-[120px]">{session.folder || '默认项目'}</span>
                      <Edit3 className="w-2.5 h-2.5 text-slate-500 opacity-60" />
                    </div>
                  )}

                  <span className="text-slate-600">·</span>
                  <span className="truncate max-w-[120px] text-slate-500" title={session.cwd}>
                    {session.cwd}
                  </span>
                </div>
              </div>

              <div className="flex items-center gap-1.5 shrink-0">
                {getStatusBadge(session.status)}
                <button
                  onClick={() => {
                    const msg = session.status === 'stopped'
                      ? `确定要彻底删除终端【${session.name}】吗？`
                      : `终端【${session.name}】正在运行中，确定要关闭并彻底删除吗？`;
                    if (window.confirm(msg)) {
                      onDelete && onDelete(session.session_id);
                    }
                  }}
                  title="删除此会话"
                  className="p-1 rounded-md bg-rose-500/10 hover:bg-rose-500/25 text-rose-400 hover:text-rose-200 border border-rose-500/30 transition-colors cursor-pointer"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>

            {/* Metrics */}
            <div className="mt-3.5 pt-2.5 border-t border-slate-800/80 grid grid-cols-3 gap-1 text-[11px] font-mono text-slate-400">
              <div>
                <span className="text-[10px] text-slate-500 block uppercase">PID 进程</span>
                <span className="text-slate-200">{session.pid || '—'}</span>
              </div>
              <div>
                <span className="text-[10px] text-slate-500 block uppercase">运行时间</span>
                <span className="text-slate-200">{formatUptime(session.uptime_seconds)}</span>
              </div>
              <div>
                <span className="text-[10px] text-slate-500 block uppercase">输出流量</span>
                <span className="text-slate-200">{formatBytes(session.total_bytes_out)}</span>
              </div>
            </div>

            {/* Actions */}
            <div className="mt-3.5 flex items-center gap-2 flex-wrap sm:flex-nowrap">
              {session.status === 'stopped' ? (
                <>
                  <button
                    onClick={() => onRestart && onRestart(session.session_id)}
                    className="flex-1 flex items-center justify-center gap-1.5 py-1.5 px-3 rounded-lg bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-300 border border-emerald-500/40 font-medium text-xs transition-all active:scale-98 shadow-sm cursor-pointer"
                    title="在原目录重新开启终端"
                  >
                    <Play className="w-3.5 h-3.5 fill-emerald-300" />
                    <span>重新开启</span>
                  </button>
                  <button
                    onClick={() => onAttach(session.session_id)}
                    title="查看历史终端输出"
                    className="flex items-center gap-1 py-1.5 px-2.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 text-xs transition-colors cursor-pointer"
                  >
                    <Terminal className="w-3.5 h-3.5" />
                    <span className="hidden sm:inline">查看</span>
                  </button>
                  <button
                    onClick={() => {
                      if (window.confirm(`确定要彻底删除终端【${session.name}】吗？`)) {
                        onDelete && onDelete(session.session_id);
                      }
                    }}
                    title="彻底删除此会话记录"
                    className="flex items-center gap-1 py-1.5 px-3 rounded-lg bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 border border-rose-500/50 text-xs font-semibold transition-colors cursor-pointer shadow-sm"
                  >
                    <Trash2 className="w-3.5 h-3.5 text-rose-400" />
                    <span>删除</span>
                  </button>
                </>
              ) : (
                <>
                  <button
                    onClick={() => onAttach(session.session_id)}
                    className="flex-1 flex items-center justify-center gap-1.5 py-1.5 px-3 rounded-lg bg-cyan-600/20 hover:bg-cyan-600/30 text-cyan-300 border border-cyan-500/40 font-medium text-xs transition-all active:scale-98 shadow-sm cursor-pointer"
                  >
                    <Terminal className="w-3.5 h-3.5" />
                    <span>进入终端</span>
                  </button>
                  <button
                    onClick={() => onTerminate(session.session_id)}
                    title="关闭并终止此进程"
                    className="flex items-center gap-1 py-1.5 px-2.5 rounded-lg bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 border border-amber-500/30 text-xs font-medium transition-colors cursor-pointer"
                  >
                    <Square className="w-3 h-3 fill-amber-300" />
                    <span>关闭</span>
                  </button>
                  <button
                    onClick={() => {
                      if (window.confirm(`终端【${session.name}】正在运行中，确定要关闭并彻底删除吗？`)) {
                        onDelete && onDelete(session.session_id);
                      }
                    }}
                    title="强制关闭并彻底删除此终端"
                    className="flex items-center gap-1 py-1.5 px-3 rounded-lg bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 border border-rose-500/50 text-xs font-semibold transition-colors cursor-pointer shadow-sm"
                  >
                    <Trash2 className="w-3.5 h-3.5 text-rose-400" />
                    <span>删除</span>
                  </button>
                </>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
