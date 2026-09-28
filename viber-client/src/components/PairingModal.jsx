import React, { useEffect, useState, useRef } from 'react';
import { parsePairingBundle } from '../services/pairing.js';

export default function PairingModal({ isOpen, onClose, currentConfig, onSaveConfig }) {
  const [input, setInput] = useState('');
  const [candidate, setCandidate] = useState(null);
  const [error, setError] = useState('');
  const [parsing, setParsing] = useState(false);
  const parseGeneration = useRef(0);
  useEffect(() => { ++parseGeneration.current; setParsing(false); if (!isOpen) { setInput(''); setCandidate(null); setError(''); } }, [isOpen]);
  if (!isOpen) return null;
  const parse = async () => {
    const generation = ++parseGeneration.current;
    setError(''); setCandidate(null); setParsing(true);
    try { const parsed = await parsePairingBundle(input); if (generation === parseGeneration.current) setCandidate(parsed); }
    catch (e) { if (generation === parseGeneration.current) setError(e.message); }
    finally { if (generation === parseGeneration.current) setParsing(false); }
  };
  const save = () => {
    if (!candidate) return;
    // The full key is retained, not learned from a subsequent network response.
    onSaveConfig(candidate);
    setInput(''); setCandidate(null); onClose();
  };
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4">
      <section role="dialog" aria-modal="true" aria-label="安全配对" className="w-full max-w-lg rounded-xl bg-slate-900 p-5 text-slate-100 space-y-4">
        <h2 className="text-lg font-semibold">主机身份确认与安全配对</h2>
        <p className="text-sm">在目标主机本地运行 <code>python main.py --pair-info</code>，粘贴完整 v2 配对链接。不会从网络自动获取凭据。</p>
        <p className="text-sm text-amber-200">仅在可信的 localhost 或 HTTPS 页面输入凭据。请与主机终端核对完整 SHA-256 指纹；网页上的主机名称不能证明身份。</p>
        <textarea aria-label="配对链接" disabled={parsing} autoComplete="off" spellCheck="false" rows={3}
          className="w-full rounded bg-slate-950 p-2 text-xs" value={input}
          onChange={(e) => { setInput(e.target.value); setCandidate(null); }} />
        <button type="button" disabled={parsing} className="rounded bg-slate-700 px-3 py-2" onClick={parse}>解析并预览</button>
        {error && <p role="alert" className="text-sm text-red-300">{error}</p>}
        {candidate && <div className="space-y-2 text-sm">
          <p>主机：{candidate.hostName}</p>
          <p className="break-all font-mono">SHA-256：{candidate.fingerprint}</p>
          <p className="break-all">地址：{candidate.directUrl || [...candidate.tailscaleIps, ...candidate.lanIps].join(', ')}</p>
          {currentConfig?.hostPub && currentConfig.hostPub !== candidate.hostPub && <p className="text-amber-200">这不是当前已保存的身份。确认后会切换主机。</p>}
          <p>凭据仅保存在当前浏览器标签页会话中。</p>
          <button type="button" className="rounded bg-cyan-700 px-3 py-2" onClick={save}>已核对指纹，确认信任并保存</button>
        </div>}
        <button type="button" className="ml-3 rounded border border-slate-600 px-3 py-2" onClick={onClose}>取消</button>
      </section>
    </div>
  );
}
