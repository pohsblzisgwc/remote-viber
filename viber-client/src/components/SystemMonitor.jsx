import React from 'react';
import { Cpu, HardDrive, Network, Zap } from 'lucide-react';

export default function SystemMonitor({ stats }) {
  if (!stats || !stats.system) return null;

  const sys = stats.system;
  const cpuPct = sys.cpu_percent || 0;
  const memPct = sys.memory_percent || 0;
  const memUsed = (sys.memory_used_mb / 1024).toFixed(1);
  const memTotal = (sys.memory_total_mb / 1024).toFixed(1);

  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
      {/* CPU */}
      <div className="p-3 rounded-xl bg-[#0c1220]/70 border border-slate-800">
        <div className="flex items-center justify-between text-xs text-slate-400 mb-1.5">
          <span className="flex items-center gap-1.5 font-medium">
            <Cpu className="w-3.5 h-3.5 text-cyan-400" />
            <span>宿主 CPU 占用</span>
          </span>
          <span className="font-mono text-slate-200">{cpuPct}%</span>
        </div>
        <div className="w-full h-1.5 bg-slate-900 rounded-full overflow-hidden">
          <div
            className={`h-full transition-all duration-500 rounded-full ${
              cpuPct > 80 ? 'bg-rose-500' : cpuPct > 50 ? 'bg-amber-400' : 'bg-cyan-400'
            }`}
            style={{ width: `${Math.min(100, Math.max(2, cpuPct))}%` }}
          />
        </div>
      </div>

      {/* Memory */}
      <div className="p-3 rounded-xl bg-[#0c1220]/70 border border-slate-800">
        <div className="flex items-center justify-between text-xs text-slate-400 mb-1.5">
          <span className="flex items-center gap-1.5 font-medium">
            <HardDrive className="w-3.5 h-3.5 text-purple-400" />
            <span>宿主 内存占用</span>
          </span>
          <span className="font-mono text-slate-200">{memPct}%</span>
        </div>
        <div className="w-full h-1.5 bg-slate-900 rounded-full overflow-hidden">
          <div
            className={`h-full transition-all duration-500 rounded-full ${
              memPct > 85 ? 'bg-rose-500' : 'bg-purple-400'
            }`}
            style={{ width: `${Math.min(100, Math.max(2, memPct))}%` }}
          />
        </div>
        <div className="text-[10px] text-slate-500 font-mono mt-1 text-right">
          {memUsed} / {memTotal} GB
        </div>
      </div>

      {/* Tailscale / Network Status */}
      <div className="p-3 rounded-xl bg-[#0c1220]/70 border border-slate-800">
        <div className="text-xs text-slate-400 flex items-center gap-1.5 mb-1">
          <Zap className="w-3.5 h-3.5 text-cyan-400" />
          <span className="font-medium">Tailscale 虚拟直连</span>
        </div>
        <div className="text-[11px] font-mono text-cyan-300 truncate">
          {sys.endpoints?.tailscale?.[0] || '100.x.y.z 已就绪'}
        </div>
        <div className="text-[10px] text-slate-500 font-mono mt-0.5">零配置点对点极低延迟</div>
      </div>

      {/* Security Engine */}
      <div className="p-3 rounded-xl bg-[#0c1220]/70 border border-slate-800">
        <div className="text-xs text-slate-400 flex items-center gap-1.5 mb-1">
          <Network className="w-3.5 h-3.5 text-emerald-400" />
          <span className="font-medium">E2EE 安全密道</span>
        </div>
        <div className="text-[11px] font-mono text-emerald-300">P-256 + AES-GCM</div>
        <div className="text-[10px] text-slate-500 font-mono mt-0.5">硬件加速端到端保密</div>
      </div>
    </div>
  );
}
