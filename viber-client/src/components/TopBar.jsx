import React from 'react';
import { 
  Zap, 
  Globe, 
  Home, 
  WifiOff, 
  ShieldCheck, 
  Layers, 
  Terminal, 
  Plus, 
  QrCode, 
  Activity,
  Server,
  TerminalSquare
} from 'lucide-react';

export default function TopBar({
  connectionState,
  connectionMode,
  pingMs,
  activeView,
  setActiveView,
  onOpenLaunchModal,
  onQuickTerminal,
  onOpenPairingModal,
  hostConfig,
  systemStats,
  activeSessionsCount,
}) {
  const getModeBadge = () => {
    if (connectionState === 'connected') {
      if (connectionMode === 'tailscale') {
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-cyan-500/10 text-cyan-400 border border-cyan-500/30 text-xs font-medium tracking-wide">
            <Zap className="w-3.5 h-3.5 fill-cyan-400 animate-pulse" />
            <span>Tailscale 直连</span>
          </span>
        );
      }
      if (connectionMode === 'lan' || connectionMode === 'localhost') {
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 text-xs font-medium tracking-wide">
            <Home className="w-3.5 h-3.5" />
            <span>局域网直连</span>
          </span>
        );
      }
      return (
        <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-purple-500/10 text-purple-400 border border-purple-500/30 text-xs font-medium tracking-wide">
          <Globe className="w-3.5 h-3.5 animate-spin-slow" />
          <span>公网中继穿透</span>
        </span>
      );
    }
    if (connectionState === 'error') {
      return (
        <button
          onClick={onOpenPairingModal}
          className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/40 text-xs font-medium tracking-wide hover:bg-rose-500/30 transition-colors cursor-pointer"
          title="点击重新输入配对口令或导入配置"
        >
          <WifiOff className="w-3.5 h-3.5" />
          <span>配对口令错误 (点击配置)</span>
        </button>
      );
    }
    if (connectionState === 'reconnecting' || connectionState === 'connecting' || connectionState === 'handshake') {
      return (
        <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-amber-500/10 text-amber-400 border border-amber-500/30 text-xs font-medium tracking-wide">
          <Activity className="w-3.5 h-3.5 animate-bounce" />
          <span>{connectionState === 'handshake' ? 'E2EE 加密握手中...' : '正在建立连接...'}</span>
        </span>
      );
    }
    return (
      <button
        onClick={onOpenPairingModal}
        className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-800/80 text-slate-400 border border-slate-700/50 text-xs font-medium tracking-wide hover:bg-slate-700 transition-colors cursor-pointer"
        title="点击配置连接"
      >
        <WifiOff className="w-3.5 h-3.5" />
        <span>未连接 (点击配置)</span>
      </button>
    );
  };

  return (
    <header 
      className="border-b border-[#1e293b] bg-[#0c1220]/95 backdrop-blur-md px-2.5 sm:px-4 md:px-5 flex items-center justify-between z-30 select-none shrink-0"
      style={{
        paddingTop: 'calc(env(safe-area-inset-top, 0px) + 0.35rem)',
        paddingBottom: '0.35rem',
        minHeight: 'calc(3.25rem + env(safe-area-inset-top, 0px))',
      }}
    >
      {/* Brand & Host Identity */}
      <div className="flex items-center gap-2 sm:gap-3 shrink-0">
        <div className="flex items-center gap-1.5 sm:gap-2 cursor-pointer" onClick={() => setActiveView('dashboard')}>
          <div className="w-7 h-7 sm:w-8 sm:h-8 rounded-lg bg-gradient-to-tr from-cyan-500 via-indigo-500 to-purple-600 flex items-center justify-center shadow-lg shadow-cyan-500/20 shrink-0">
            <Zap className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-white fill-white" />
          </div>
          <div className="flex flex-col">
            <div className="flex items-center gap-1.5">
              <span className="font-bold text-xs sm:text-sm tracking-wider bg-clip-text text-transparent bg-gradient-to-r from-white via-slate-200 to-slate-400 font-mono">
                REMOTE<span className="text-cyan-400">VIBER</span>
              </span>
              <span className="hidden md:inline-block text-[9px] px-1.5 py-0.2 rounded bg-slate-800 text-slate-400 font-mono">
                矩阵
              </span>
            </div>
            
            {/* Mobile Connection Status under logo */}
            <div className="flex sm:hidden items-center gap-1 cursor-pointer" onClick={onOpenPairingModal}>
              <span className={`w-1.5 h-1.5 rounded-full ${
                connectionState === 'connected' ? 'bg-cyan-400 animate-pulse' :
                connectionState === 'handshake' || connectionState === 'connecting' ? 'bg-amber-400 animate-ping' :
                'bg-rose-500'
              }`} />
              <span className="text-[10px] text-slate-400 font-mono truncate max-w-[85px]">
                {connectionState === 'connected' ? (connectionMode === 'tailscale' ? 'Tailscale' : '直连') :
                 connectionState === 'handshake' ? '握手中...' :
                 connectionState === 'connecting' ? '连接中...' : '点击配对'}
              </span>
            </div>

            <div className="hidden sm:flex text-[10px] text-slate-400 items-center gap-1 truncate max-w-[120px] md:max-w-none">
              <Server className="w-2.5 h-2.5 text-slate-500" />
              <span className="truncate">{hostConfig?.hostName || '目标主机'}</span>
            </div>
          </div>
        </div>

        {/* Desktop Connection status badges */}
        <div className="hidden sm:flex items-center gap-2 ml-1">
          {getModeBadge()}

          {connectionState === 'connected' && pingMs > 0 && (
            <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-slate-800/80 text-slate-400 border border-slate-700/50 flex items-center gap-1">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
              {pingMs}ms
            </span>
          )}

          <div className="hidden lg:flex items-center gap-1 text-[10px] text-emerald-400/90 font-mono px-2 py-0.5 rounded bg-emerald-950/30 border border-emerald-800/30">
            <ShieldCheck className="w-3 h-3 text-emerald-400" />
            <span>E2EE 加密</span>
          </div>
        </div>
      </div>

      {/* Navigation & Controls */}
      <div className="flex items-center gap-1.5 sm:gap-2">
        {/* View Switcher */}
        <div className="flex items-center p-0.5 rounded-lg bg-slate-900 border border-slate-800 text-xs font-medium">
          <button
            onClick={() => setActiveView('dashboard')}
            className={`flex items-center gap-1 px-2 sm:px-3 py-1 sm:py-1.5 rounded-md transition-all ${
              activeView === 'dashboard'
                ? 'bg-gradient-to-r from-cyan-500/20 to-indigo-500/20 text-cyan-300 shadow-sm border border-cyan-500/30'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">控制面板</span>
            <span className="inline sm:hidden text-[11px]">看板</span>
          </button>
          <button
            onClick={() => setActiveView('terminal')}
            className={`flex items-center gap-1 px-2 sm:px-3 py-1 sm:py-1.5 rounded-md transition-all relative ${
              activeView === 'terminal'
                ? 'bg-gradient-to-r from-cyan-500/20 to-indigo-500/20 text-cyan-300 shadow-sm border border-cyan-500/30'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">终端工作台</span>
            <span className="inline sm:hidden text-[11px]">终端</span>
            {activeSessionsCount > 0 && (
              <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 absolute top-1 right-1"></span>
            )}
          </button>
        </div>

        {/* 1-Click Quick Pure Terminal Button */}
        <button
          onClick={onQuickTerminal}
          title="直接在远程目标机开启纯交互终端"
          className="flex items-center gap-1 p-1.5 sm:px-2.5 sm:py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 hover:text-white border border-slate-700 font-medium text-xs transition-all active:scale-95"
        >
          <TerminalSquare className="w-4 h-4 text-cyan-400 shrink-0" />
          <span className="hidden md:inline">新建终端</span>
        </button>

        {/* 1-Click Launch Agent Button */}
        <button
          onClick={onOpenLaunchModal}
          title="启动 Agent 智能体"
          className="flex items-center gap-1 p-1.5 sm:px-2.5 sm:py-1.5 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-medium text-xs shadow-md shadow-cyan-500/20 transition-all active:scale-95"
        >
          <Plus className="w-4 h-4 shrink-0" />
          <span className="hidden md:inline">启动 Agent</span>
        </button>

        {/* Pairing / Host Settings Modal Button */}
        <button
          onClick={onOpenPairingModal}
          title="网络配对与口令设置"
          className="p-1.5 sm:p-2 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-slate-200 border border-slate-800 transition-all active:scale-95"
        >
          <QrCode className="w-4 h-4" />
        </button>
      </div>
    </header>
  );
}
