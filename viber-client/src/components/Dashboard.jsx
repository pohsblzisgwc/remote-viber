import React, { useState, useMemo } from 'react';
import { 
  Terminal, 
  Sparkles, 
  Plus, 
  Activity, 
  RefreshCw, 
  Folder, 
  TerminalSquare, 
  Layers, 
  LayoutGrid, 
  FolderTree,
  ChevronDown,
  ChevronRight,
  FolderPlus
} from 'lucide-react';
import AgentCard from './AgentCard';
import RunningAgentsGrid from './RunningAgentsGrid';
import SystemMonitor from './SystemMonitor';

export default function Dashboard({
  profiles,
  sessions,
  systemStats,
  connectionState,
  onQuickLaunch,
  onConfigureLaunch,
  onQuickTerminal,
  onAttachSession,
  onTerminateSession,
  onRestartSession,
  onDeleteSession,
  onUpdateSessionFolder,
  onOpenNewProfileModal,
  onDeleteProfile,
  onRefresh,
}) {
  const [selectedFolderFilter, setSelectedFolderFilter] = useState('ALL');
  const [viewMode, setViewMode] = useState('grouped'); // 'grouped' | 'flat'
  const [collapsedFolders, setCollapsedFolders] = useState({});

  // Extract all unique project folders from active sessions
  const folderGroups = useMemo(() => {
    const map = {};
    sessions.forEach((s) => {
      const folderKey = s.folder || (s.cwd ? s.cwd.split(/[/\\]/).filter(Boolean).pop() : '默认项目');
      if (!map[folderKey]) {
        map[folderKey] = {
          folderName: folderKey,
          cwd: s.cwd,
          sessions: [],
        };
      }
      map[folderKey].sessions.push(s);
    });
    return map;
  }, [sessions]);

  const uniqueFolderNames = Object.keys(folderGroups);

  const toggleFolderCollapse = (folderName) => {
    setCollapsedFolders((prev) => ({
      ...prev,
      [folderName]: !prev[folderName],
    }));
  };

  // Filtered sessions for flat view or filter tab
  const displayedSessions = useMemo(() => {
    if (selectedFolderFilter === 'ALL') return sessions;
    return sessions.filter((s) => {
      const folderKey = s.folder || (s.cwd ? s.cwd.split(/[/\\]/).filter(Boolean).pop() : '默认项目');
      return folderKey === selectedFolderFilter;
    });
  }, [sessions, selectedFolderFilter]);

  return (
    <div 
      className="flex-1 overflow-y-auto p-4 md:p-6 space-y-6 max-w-7xl mx-auto w-full select-none"
      style={{
        paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 2.5rem)',
      }}
    >
      {/* System Resources Overview */}
      <SystemMonitor stats={systemStats} />

      {/* 1-Click Launchers Grid */}
      <section>
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <Sparkles className="w-4 h-4 text-cyan-400" />
            <h2 className="text-sm font-semibold tracking-wide text-slate-100 uppercase font-mono">
              自定义预设启动器 (1-Click 快捷拉起)
            </h2>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => onQuickTerminal()}
              className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium border border-slate-700 transition-colors"
            >
              <TerminalSquare className="w-3.5 h-3.5 text-cyan-400" />
              <span>新建纯终端</span>
            </button>
            <button
              onClick={onOpenNewProfileModal}
              className="flex items-center gap-1 text-xs text-cyan-400 hover:text-cyan-300 font-medium transition-colors ml-1"
            >
              <Plus className="w-3.5 h-3.5" />
              <span>新增预设</span>
            </button>
          </div>
        </div>

        {(!profiles || profiles.length === 0) ? (
          <div className="p-6 rounded-2xl border border-dashed border-slate-800 bg-[#0e1626]/40 flex flex-col items-center justify-center text-center py-8">
            <div className="w-12 h-12 rounded-xl bg-cyan-500/10 text-cyan-400 flex items-center justify-center border border-cyan-500/20 mb-3">
              <Sparkles className="w-6 h-6" />
            </div>
            <h3 className="font-semibold text-slate-200 text-sm mb-1">暂无已保存预设</h3>
            <p className="text-xs text-slate-400 max-w-md mb-4 leading-relaxed">
              您可以创建并保存多个预配置选项（如 Docker 运行容器、特定环境 Agent、复合多指令构建脚本），随时一键启动。
            </p>
            <button
              onClick={onOpenNewProfileModal}
              className="flex items-center gap-1.5 px-4 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-medium text-xs shadow-lg shadow-cyan-900/30 transition-all active:scale-95"
            >
              <Plus className="w-4 h-4" />
              <span>创建首个启动预设</span>
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
            {profiles.map((profile) => (
              <AgentCard
                key={profile.id}
                profile={profile}
                onQuickLaunch={onQuickLaunch}
                onConfigureLaunch={onConfigureLaunch}
                onDeleteProfile={onDeleteProfile}
              />
            ))}
          </div>
        )}
      </section>

      {/* Active Agent Matrix & Folder Management */}
      <section className="space-y-3">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-1">
          <div className="flex items-center gap-2">
            <Activity className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-semibold tracking-wide text-slate-100 uppercase font-mono flex items-center gap-2">
              <span>活动会话矩阵</span>
              <span className="text-xs px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 font-normal">
                {sessions?.length || 0} 个会话
              </span>
            </h2>
          </div>

          <div className="flex items-center gap-2">
            {/* View Mode Toggle */}
            <div className="flex items-center p-0.5 rounded-lg bg-slate-900 border border-slate-800 text-xs">
              <button
                onClick={() => setViewMode('grouped')}
                className={`flex items-center gap-1 px-2.5 py-1 rounded-md transition-all ${
                  viewMode === 'grouped'
                    ? 'bg-slate-800 text-cyan-300 shadow-sm font-medium'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <FolderTree className="w-3.5 h-3.5" />
                <span>分级项目视图</span>
              </button>
              <button
                onClick={() => setViewMode('flat')}
                className={`flex items-center gap-1 px-2.5 py-1 rounded-md transition-all ${
                  viewMode === 'flat'
                    ? 'bg-slate-800 text-cyan-300 shadow-sm font-medium'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <LayoutGrid className="w-3.5 h-3.5" />
                <span>平铺视图</span>
              </button>
            </div>

            <button
              onClick={onRefresh}
              title="刷新会话与系统状态"
              className="p-1.5 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-slate-200 border border-slate-800 transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* Project Folder Filter Tabs (when folders exist) */}
        {uniqueFolderNames.length > 1 && (
          <div className="flex items-center gap-1.5 overflow-x-auto pb-1 text-xs no-scrollbar">
            <button
              onClick={() => setSelectedFolderFilter('ALL')}
              className={`px-3 py-1.5 rounded-lg font-medium transition-all shrink-0 ${
                selectedFolderFilter === 'ALL'
                  ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 shadow-sm'
                  : 'bg-slate-900 text-slate-400 hover:text-slate-200 border border-slate-800'
              }`}
            >
              全部项目 ({sessions.length})
            </button>
            {uniqueFolderNames.map((folderName) => {
              const count = folderGroups[folderName].sessions.length;
              return (
                <button
                  key={folderName}
                  onClick={() => setSelectedFolderFilter(folderName)}
                  className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg font-medium transition-all shrink-0 ${
                    selectedFolderFilter === folderName
                      ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 shadow-sm'
                      : 'bg-slate-900 text-slate-400 hover:text-slate-200 border border-slate-800'
                  }`}
                >
                  <Folder className="w-3.5 h-3.5 text-purple-400" />
                  <span>{folderName}</span>
                  <span className="text-[10px] px-1.5 py-0.2 rounded-full bg-slate-800 text-slate-400">
                    {count}
                  </span>
                </button>
              );
            })}
          </div>
        )}

        {/* Render View based on mode */}
        {viewMode === 'grouped' && selectedFolderFilter === 'ALL' ? (
          <div className="space-y-4">
            {uniqueFolderNames.length === 0 ? (
              <RunningAgentsGrid
                sessions={[]}
                onAttach={onAttachSession}
                onTerminate={onTerminateSession}
                onUpdateFolder={onUpdateSessionFolder}
              />
            ) : (
              uniqueFolderNames.map((folderName) => {
                const group = folderGroups[folderName];
                const isCollapsed = collapsedFolders[folderName];

                return (
                  <div
                    key={folderName}
                    className="rounded-2xl border border-slate-800/90 bg-[#0a0f1e]/80 overflow-hidden shadow-lg"
                  >
                    {/* Folder Group Header Bar */}
                    <div
                      onClick={() => toggleFolderCollapse(folderName)}
                      className="p-3.5 bg-slate-900/60 hover:bg-slate-900 cursor-pointer flex items-center justify-between border-b border-slate-800/60 transition-colors"
                    >
                      <div className="flex items-center gap-2.5 truncate">
                        <button className="text-slate-500 hover:text-slate-300">
                          {isCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                        </button>
                        <div className="w-7 h-7 rounded-lg bg-purple-500/10 text-purple-400 border border-purple-500/30 flex items-center justify-center shrink-0">
                          <Folder className="w-4 h-4 fill-purple-400/20" />
                        </div>
                        <div className="truncate">
                          <span className="font-semibold text-sm text-slate-100 truncate mr-2">
                            {folderName}
                          </span>
                          <span className="text-[11px] font-mono text-slate-500 truncate hidden sm:inline">
                            {group.cwd}
                          </span>
                        </div>
                        <span className="text-[10px] px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 font-mono">
                          {group.sessions.length} 个活动会话
                        </span>
                      </div>

                      {/* Quick launch inside this folder */}
                      <div className="flex items-center gap-1.5" onClick={(e) => e.stopPropagation()}>
                        <button
                          onClick={() => onQuickTerminal(group.cwd, folderName)}
                          className="flex items-center gap-1 px-2.5 py-1 rounded-md bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-white text-xs border border-slate-700 transition-colors"
                        >
                          <TerminalSquare className="w-3.5 h-3.5 text-cyan-400" />
                          <span className="hidden sm:inline">开终端</span>
                        </button>
                        <button
                          onClick={() => onConfigureLaunch({ default_cwd: group.cwd, folder: folderName })}
                          className="flex items-center gap-1 px-2.5 py-1 rounded-md bg-cyan-950/40 hover:bg-cyan-900/50 text-cyan-300 text-xs border border-cyan-800/60 transition-colors"
                        >
                          <Plus className="w-3.5 h-3.5" />
                          <span className="hidden sm:inline">拉起 Agent</span>
                        </button>
                      </div>
                    </div>

                    {/* Folder Sessions Grid */}
                    {!isCollapsed && (
                      <div className="p-3">
                        <RunningAgentsGrid
                          sessions={group.sessions}
                          onAttach={onAttachSession}
                          onTerminate={onTerminateSession}
                          onRestart={onRestartSession}
                          onDelete={onDeleteSession}
                          onUpdateFolder={onUpdateSessionFolder}
                        />
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        ) : (
          <RunningAgentsGrid
            sessions={displayedSessions}
            onAttach={onAttachSession}
            onTerminate={onTerminateSession}
            onRestart={onRestartSession}
            onDelete={onDeleteSession}
            onUpdateFolder={onUpdateSessionFolder}
          />
        )}
      </section>
    </div>
  );
}
