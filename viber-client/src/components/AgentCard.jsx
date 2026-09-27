import React from 'react';
import { Sparkles, Bot, Zap, Terminal, Play, Settings2, FolderGit2, Trash2 } from 'lucide-react';

const ICON_MAP = {
  sparkles: Sparkles,
  bot: Bot,
  zap: Zap,
  terminal: Terminal,
};

export default function AgentCard({ profile, onQuickLaunch, onConfigureLaunch, onDeleteProfile }) {
  const IconComponent = ICON_MAP[profile.icon] || (profile.command?.includes('docker') ? Zap : Bot);

  const handleDelete = (e) => {
    e.stopPropagation();
    if (window.confirm(`确定要删除预设【${profile.name}】吗？`)) {
      onDeleteProfile?.(profile.id);
    }
  };

  return (
    <div className="group relative rounded-xl border border-slate-800 bg-[#0e1626]/80 p-4 transition-all duration-300 hover:border-cyan-500/40 hover:bg-[#111c30] hover:shadow-xl hover:shadow-cyan-950/30 flex flex-col justify-between">
      {/* Background glow */}
      <div className={`absolute -top-12 -right-12 w-28 h-28 rounded-full bg-gradient-to-br ${profile.gradient || 'from-cyan-500/10 to-transparent'} blur-2xl pointer-events-none group-hover:opacity-100 opacity-40 transition-opacity`}></div>

      <div>
        {/* Header with Icon, Category & Delete */}
        <div className="flex items-center justify-between mb-3">
          <div className={`w-10 h-10 rounded-lg bg-gradient-to-tr ${profile.gradient || 'from-cyan-500 to-blue-600'} p-0.5 shadow-md`}>
            <div className="w-full h-full bg-[#0a0f1d] rounded-[7px] flex items-center justify-center">
              <IconComponent className="w-5 h-5 text-white" />
            </div>
          </div>

          <div className="flex items-center gap-1.5">
            <span className="text-[10px] font-mono tracking-wider px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 border border-slate-700/50">
              {profile.category === 'system' ? '系统外壳' : (profile.command?.includes('docker') ? 'Docker 容器' : '预设指令')}
            </span>
            {onDeleteProfile && (
              <button
                onClick={handleDelete}
                title="删除此预设"
                className="p-1 rounded-md text-slate-500 hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            )}
          </div>
        </div>

        {/* Title & Description */}
        <h3 className="font-semibold text-slate-100 text-sm group-hover:text-cyan-300 transition-colors flex items-center gap-1.5">
          {profile.name}
        </h3>
        {profile.description && (
          <p className="text-xs text-slate-400 mt-1 line-clamp-2 leading-relaxed">
            {profile.description}
          </p>
        )}

        {/* Command & Directory Pill */}
        <div className="mt-3 py-1.5 px-2 rounded-md bg-slate-950/60 border border-slate-800/80 font-mono text-[11px] text-slate-300 truncate flex items-center gap-1.5">
          <Terminal className="w-3 h-3 text-cyan-400 shrink-0" />
          <span className="truncate">{Array.isArray(profile.command) ? profile.command.join(' ') : profile.command}</span>
        </div>

        {profile.default_cwd && (
          <div className="mt-1.5 text-[10px] text-slate-500 font-mono flex items-center gap-1 truncate">
            <FolderGit2 className="w-3 h-3 text-slate-500 shrink-0" />
            <span className="truncate">{profile.default_cwd}</span>
          </div>
        )}
      </div>

      {/* Action Footer */}
      <div className="mt-4 pt-3 border-t border-slate-800/60 flex items-center gap-2">
        <button
          onClick={() => onQuickLaunch(profile)}
          className="flex-1 flex items-center justify-center gap-1.5 py-1.5 px-3 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-medium text-xs shadow-md shadow-cyan-900/20 active:scale-98 transition-all"
        >
          <Play className="w-3.5 h-3.5 fill-white" />
          <span>一键拉起</span>
        </button>

        <button
          onClick={() => onConfigureLaunch(profile)}
          title="修改配置或参数启动"
          className="p-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-slate-200 border border-slate-800 transition-colors"
        >
          <Settings2 className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
}
