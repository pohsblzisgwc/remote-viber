import React, { useState } from 'react';
import { X, QrCode, Shield, Server, Check, ArrowRight, Smartphone, Laptop } from 'lucide-react';

export default function PairingModal({
  isOpen,
  onClose,
  currentConfig,
  onSaveConfig,
}) {
  const [pairingString, setPairingString] = useState('');
  const [hostName, setHostName] = useState(currentConfig?.hostName || '开发主机');
  const [tailscaleIp, setTailscaleIp] = useState(currentConfig?.tailscaleIps?.[0] || '');
  const [lanIp, setLanIp] = useState(currentConfig?.lanIps?.[0] || '127.0.0.1');
  const [port, setPort] = useState(currentConfig?.directPort || 8765);
  const [token, setToken] = useState(currentConfig?.token || '');
  const [relayUrl, setRelayUrl] = useState(currentConfig?.relayUrl || '');
  const [parseError, setParseError] = useState('');
  const [importSuccess, setImportSuccess] = useState(false);

  const handleAutoDetect = async () => {
    setParseError('');
    setImportSuccess(false);
    try {
      const res = await fetch('/api/pairing');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      if (data && data.token) {
        if (data.name) setHostName(data.name);
        if (data.port) setPort(data.port);
        if (data.token) setToken(data.token);
        if (data.relay) setRelayUrl(data.relay);
        if (data.tailscale && data.tailscale.length > 0) setTailscaleIp(data.tailscale[0]);
        if (data.lan && data.lan.length > 0) setLanIp(data.lan[0]);
        setImportSuccess(true);
      } else {
        setParseError('未从当前服务获取到有效配对令牌');
      }
    } catch (e) {
      setParseError('当前环境无法直接访问 /api/pairing: ' + e.message + '，请粘贴终端配对链接');
    }
  };

  const handleParsePairingString = () => {
    setParseError('');
    setImportSuccess(false);

    try {
      let raw = pairingString.trim();
      if (raw.startsWith('viber://connect?data=')) {
        raw = raw.replace('viber://connect?data=', '');
      }

      // Base64 decode URL safe
      let base64 = raw.replace(/-/g, '+').replace(/_/g, '/');
      while (base64.length % 4) {
        base64 += '=';
      }

      const jsonStr = decodeURIComponent(escape(window.atob(base64)));
      const data = JSON.parse(jsonStr);

      if (data.name) setHostName(data.name);
      if (data.port) setPort(data.port);
      if (data.token) setToken(data.token);
      if (data.relay) setRelayUrl(data.relay);
      if (data.tailscale && data.tailscale.length > 0) setTailscaleIp(data.tailscale[0]);
      if (data.lan && data.lan.length > 0) setLanIp(data.lan[0]);

      setImportSuccess(true);
    } catch (e) {
      setParseError('无效的配对码格式，请检查从终端复制的完整字符串。');
    }
  };

  const handleSave = () => {
    const config = {
      hostId: currentConfig?.hostId || 'host-custom',
      hostName: hostName.trim() || '开发主机',
      directPort: parseInt(port) || 8765,
      tailscaleIps: tailscaleIp.trim() ? [tailscaleIp.trim()] : [],
      lanIps: lanIp.trim() ? [lanIp.trim()] : ['127.0.0.1'],
      token: token.trim(),
      relayUrl: relayUrl.trim(),
    };
    onSaveConfig(config);
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-4 bg-black/75 backdrop-blur-sm animate-fade-in">
      <div className="w-full max-w-lg max-h-[92dvh] flex flex-col rounded-2xl border border-slate-800 bg-[#0d1424] shadow-2xl text-slate-200 overflow-hidden">
        {/* Header */}
        <div className="shrink-0 p-4 sm:p-5 pb-3 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-cyan-500/20 text-cyan-400 flex items-center justify-center border border-cyan-500/30 shrink-0">
              <QrCode className="w-4 h-4" />
            </div>
            <div>
              <h3 className="font-semibold text-sm text-slate-100">宿主连接与配对设置</h3>
              <p className="text-[11px] text-slate-500">一次性配对完成硬件信任，支持 Tailscale 与局域网直连</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-slate-200">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Scrollable Content */}
        <div className="flex-1 overflow-y-auto p-4 sm:p-5 space-y-4">
          {/* 1-Click Fast Import */}
          <div className="p-3 rounded-xl bg-slate-900/80 border border-cyan-500/30">
            <div className="flex items-center justify-between mb-1.5 flex-wrap gap-1">
              <label className="text-xs font-semibold text-cyan-400 flex items-center gap-1.5">
                <Smartphone className="w-3.5 h-3.5" />
                <span>一键导入终端配对字符 / 链接</span>
              </label>
              <button
                onClick={handleAutoDetect}
                className="text-[11px] text-cyan-300 hover:text-cyan-200 underline flex items-center gap-1"
                title="若当前浏览器直接访问本宿主，可自动提取配对凭证"
              >
                ⚡ 自动检测本宿主配对
              </button>
            </div>
            <div className="flex flex-col sm:flex-row gap-2">
              <input
                type="text"
                value={pairingString}
                onChange={(e) => setPairingString(e.target.value)}
                placeholder="在此粘贴宿主终端输出的 viber://connect?data=..."
                className="flex-1 bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white placeholder-slate-600 focus:outline-none focus:border-cyan-500 font-mono"
              />
              <button
                onClick={handleParsePairingString}
                className="px-3 py-1.5 rounded-lg bg-cyan-600 hover:bg-cyan-500 active:scale-95 text-white text-xs font-medium transition-all shrink-0"
              >
                解析导入
              </button>
            </div>
            {parseError && <p className="text-[11px] text-rose-400 mt-1">{parseError}</p>}
            {importSuccess && (
              <p className="text-[11px] text-emerald-400 mt-1 flex items-center gap-1 font-mono">
                <Check className="w-3 h-3" /> 配对凭证解析导入成功！已自动填充下方参数。
              </p>
            )}
          </div>

          {/* Manual Config Fields */}
          <div className="space-y-3 text-xs">
            <div>
              <label className="text-slate-400 block mb-1">宿主别名 (显示名称)</label>
              <input
                type="text"
                value={hostName}
                onChange={(e) => setHostName(e.target.value)}
                className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-cyan-500"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <div>
                <label className="text-slate-400 block mb-1">⚡ Tailscale 虚拟 IP</label>
                <input
                  type="text"
                  value={tailscaleIp}
                  onChange={(e) => setTailscaleIp(e.target.value)}
                  placeholder="100.x.y.z 或 MagicDNS"
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-white font-mono placeholder-slate-600 focus:outline-none focus:border-cyan-500"
                />
              </div>
              <div>
                <label className="text-slate-400 block mb-1">🏠 局域网 / 直连 IP</label>
                <input
                  type="text"
                  value={lanIp}
                  onChange={(e) => setLanIp(e.target.value)}
                  placeholder="192.168.x.y 或 127.0.0.1"
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-white font-mono placeholder-slate-600 focus:outline-none focus:border-cyan-500"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
              <div>
                <label className="text-slate-400 block mb-1">通信端口</label>
                <input
                  type="number"
                  value={port}
                  onChange={(e) => setPort(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-cyan-500"
                />
              </div>
              <div className="sm:col-span-2">
                <label className="text-slate-400 block mb-1">配对凭证口令 (Token)</label>
                <input
                  type="password"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  placeholder="终端输出的 Pairing Secret"
                  className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-white font-mono placeholder-slate-600 focus:outline-none focus:border-cyan-500"
                />
              </div>
            </div>

            <div>
              <label className="text-slate-400 block mb-1">🌐 公网中继信令服务 (可选备选方案)</label>
              <input
                type="text"
                value={relayUrl}
                onChange={(e) => setRelayUrl(e.target.value)}
                placeholder="例如: ws://your-relay-ip:8766"
                className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-2 text-white font-mono placeholder-slate-600 focus:outline-none focus:border-cyan-500"
              />
            </div>
          </div>

          {/* Download Native Android Client */}
          <div className="p-3 rounded-xl bg-cyan-950/20 border border-cyan-500/30 flex items-center justify-between gap-3 text-xs">
            <div className="flex items-center gap-2.5">
              <Smartphone className="w-5 h-5 text-cyan-400 shrink-0" />
              <div>
                <div className="font-semibold text-cyan-200">下载 RemoteViber 原生安卓客户端</div>
                <div className="text-[11px] text-slate-400">100% 纯 Kotlin + Jetpack Compose 原生编写，无 WebView 延迟</div>
              </div>
            </div>
            <a
              href="/remote-viber.apk"
              download="remote-viber.apk"
              className="px-3 py-1.5 rounded-lg bg-cyan-500 hover:bg-cyan-400 text-black font-semibold text-xs transition-colors shrink-0 shadow-sm shadow-cyan-900/40"
            >
              下载 APK
            </a>
          </div>

          {/* Security Notice */}
          <div className="p-2.5 rounded-lg bg-emerald-950/20 border border-emerald-800/30 flex items-center gap-2 text-[11px] text-emerald-400/90 font-mono">
            <Shield className="w-4 h-4 shrink-0" />
            <span>严格 E2EE 保密：所有交互指令、终端输出均使用 Curve P-256 与 AES-256-GCM 本地加密。</span>
          </div>
        </div>

        {/* Footer */}
        <div className="shrink-0 p-3 sm:p-4 border-t border-slate-800 flex items-center justify-end gap-2 bg-[#0a0f1c]">
          <button onClick={onClose} className="px-3.5 py-1.5 sm:px-4 sm:py-2 rounded-lg text-slate-400 hover:text-slate-200 text-xs">
            取消
          </button>
          <button
            onClick={handleSave}
            className="px-4 py-2 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-medium text-xs shadow-lg shadow-cyan-900/30 active:scale-95 transition-all flex items-center gap-1.5"
          >
            <span>保存并建立安全直连</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
}
