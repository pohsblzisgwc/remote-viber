import React, { useEffect } from 'react';
import { ClipboardPaste, AlertTriangle, X, Check, ArrowRight } from 'lucide-react';

export default function SafePasteModal({
  data,
  onConfirmSafe,
  onConfirmSingleLine,
  onCancel,
}) {
  useEffect(() => {
    if (!data) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onCancel();
      } else if (e.key === 'Enter') {
        e.preventDefault();
        onConfirmSafe();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [data, onCancel, onConfirmSafe]);

  if (!data) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-xs p-4 animate-in fade-in duration-150 select-none">
      <div 
        className="bg-[#0c1220] border border-cyan-500/40 rounded-2xl shadow-2xl max-w-lg w-full p-5 flex flex-col gap-3.5 text-slate-200 ring-1 ring-white/10"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-amber-500/15 border border-amber-500/30 flex items-center justify-center text-amber-400">
              <ClipboardPaste className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white tracking-wide">
                多行文本安全粘贴提示
              </h3>
              <p className="text-[11px] text-slate-400">
                已自动拦截直接触发回车执行的行为
              </p>
            </div>
          </div>
          <span className="text-[11px] font-mono px-2.5 py-1 rounded-full bg-cyan-950/80 text-cyan-300 border border-cyan-800/80 font-bold">
            共 {data.linesCount} 行
          </span>
        </div>

        {/* Info */}
        <div className="flex items-start gap-2 text-xs text-amber-300/90 bg-amber-950/25 border border-amber-500/20 rounded-lg p-2.5">
          <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5 text-amber-400" />
          <span>
            检测到粘贴内容包含换行符。直接粘贴可能会作为回车键自动逐行执行命令，请选择处理方式：
          </span>
        </div>

        {/* Code Preview */}
        <div className="max-h-40 overflow-y-auto bg-[#060911] border border-slate-800/80 rounded-lg p-3 font-mono text-xs text-slate-300 whitespace-pre-wrap break-all select-text leading-relaxed">
          {data.text}
        </div>

        {/* Action Buttons */}
        <div className="flex flex-col sm:flex-row gap-2 pt-2 text-xs">
          <button
            onClick={onConfirmSafe}
            className="flex-1 py-2 px-3 rounded-lg bg-gradient-to-r from-cyan-600 to-blue-600 hover:from-cyan-500 hover:to-blue-500 text-white font-medium flex items-center justify-center gap-1.5 shadow-md shadow-cyan-900/30 active:scale-98 transition-all cursor-pointer"
            title="使用括号保护模式粘贴，保留多行但不会自动触发回车执行 (可直接按 Enter 确认)"
          >
            <Check className="w-3.5 h-3.5" />
            <span>安全粘贴 (不自动回车)</span>
          </button>
          <button
            onClick={onConfirmSingleLine}
            className="flex-1 py-2 px-3 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 font-medium flex items-center justify-center gap-1.5 active:scale-98 transition-all cursor-pointer"
            title="将换行符替换为分号 ';'，合并为单条命令安全输入"
          >
            <ArrowRight className="w-3.5 h-3.5" />
            <span>合并为单行粘贴</span>
          </button>
          <button
            onClick={onCancel}
            className="py-2 px-3.5 rounded-lg bg-slate-800/80 hover:bg-slate-700 text-slate-400 hover:text-white border border-slate-700/60 font-medium flex items-center justify-center transition-all cursor-pointer"
          >
            <X className="w-3.5 h-3.5" />
            <span>取消</span>
          </button>
        </div>
      </div>
    </div>
  );
}
