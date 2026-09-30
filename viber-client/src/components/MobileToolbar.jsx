import React, { useState } from 'react';
import { Send, ChevronUp, ChevronDown, Sparkles, Clipboard, ZoomIn, ZoomOut } from 'lucide-react';

const QUICK_SNIPPETS = [
  "y",
  "n",
  "/help",
  "git status",
  "docker ps",
  "确认执行，开始跑测试",
  "修复报错并重新构建",
  "解释当前修改的架构思路",
];

export default function MobileToolbar({ onSendKey, onSendPrompt, onPaste, onZoomIn, onZoomOut }) {
  const [ctrlActive, setCtrlActive] = useState(false);
  const [altActive, setAltActive] = useState(false);
  const [promptText, setPromptText] = useState('');
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  const handlePaste = async () => {
    try {
      if (navigator.clipboard && navigator.clipboard.readText) {
        const text = await navigator.clipboard.readText();
        if (text) {
          (onPaste || onSendKey)(text);
          return;
        }
      }
    } catch (e) {}
    // Fallback prompt for browsers blocking direct clipboard API
    const text = window.prompt("在此粘贴文本发送到终端:");
    if (text) {
      (onPaste || onSendKey)(text);
    }
  };

  const handleKeyClick = (key) => {
    let output = '';
    if (ctrlActive) {
      if (key === 'C' || key === 'c') output = '\x03'; // Ctrl+C
      else if (key === 'D' || key === 'd') output = '\x04'; // Ctrl+D
      else if (key === 'Z' || key === 'z') output = '\x1a'; // Ctrl+Z
      else if (key === 'L' || key === 'l') output = '\x0c'; // Ctrl+L (clear)
      else if (key === 'A' || key === 'a') output = '\x01'; // Ctrl+A
      else if (key === 'E' || key === 'e') output = '\x05'; // Ctrl+E
      else output = key;
      setCtrlActive(false);
    } else {
      switch (key) {
        case 'ESC':
          output = '\x1b';
          break;
        case 'TAB':
          output = '\t';
          break;
        case '^C':
          output = '\x03';
          break;
        case '^D':
          output = '\x04';
          break;
        case '^Z':
          output = '\x1a';
          break;
        case '^L':
          output = '\x0c';
          break;
        case 'UP':
          output = '\x1b[A';
          break;
        case 'DOWN':
          output = '\x1b[B';
          break;
        case 'RIGHT':
          output = '\x1b[C';
          break;
        case 'LEFT':
          output = '\x1b[D';
          break;
        case 'ENTER':
          output = '\r';
          break;
        default:
          output = key;
      }
    }
    onSendKey(output);
  };

  const submitPrompt = (textToSubmit) => {
    const text = textToSubmit || promptText;
    if (!text.trim()) return;
    onSendPrompt(text.trim() + '\n');
    setPromptText('');
  };

  return (
    <div 
      className="border-t border-[#1e293b] bg-[#0c1220]/95 backdrop-blur-md select-none shrink-0 z-20"
      style={{
        paddingBottom: 'max(env(safe-area-inset-bottom, 0px), 0.35rem)',
      }}
    >
      {/* Quick Prompt Drawer (Collapsible) */}
      {isDrawerOpen && (
        <div className="p-3 border-b border-slate-800 bg-[#090d16] flex flex-col gap-2">
          {/* Quick Snippets Bar */}
          <div className="flex items-center gap-1.5 overflow-x-auto pb-1 text-[11px] font-mono no-scrollbar">
            <span className="text-slate-500 flex items-center gap-1 text-[10px] shrink-0">
              <Sparkles className="w-3 h-3 text-cyan-400" />
              <span>VIBE 快捷短语:</span>
            </span>
            {QUICK_SNIPPETS.map((snip, idx) => (
              <button
                key={idx}
                onClick={() => submitPrompt(snip)}
                className="px-2.5 py-1 rounded bg-slate-800/80 hover:bg-slate-700 text-slate-300 hover:text-white border border-slate-700/60 shrink-0 transition-colors"
              >
                {snip}
              </button>
            ))}
          </div>

          {/* Prompt Input Form */}
          <div className="flex items-center gap-2">
            <textarea
              rows={1}
              value={promptText}
              onChange={(e) => setPromptText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  submitPrompt();
                }
              }}
              placeholder="输入 Agent 提示词或命令（Shift+Enter 换行）..."
              className="flex-1 bg-slate-900 border border-slate-700/80 rounded-lg px-3 py-1.5 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-cyan-500 resize-none max-h-24 leading-normal"
            />
            <button
              onClick={() => submitPrompt()}
              className="px-3 py-1.5 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-medium text-xs flex items-center gap-1 shadow-md shadow-cyan-900/20 active:scale-95 cursor-pointer shrink-0"
            >
              <Send className="w-3.5 h-3.5" />
              <span>发送</span>
            </button>
          </div>
        </div>
      )}

      {/* Main Touch Toolbar */}
      <div className="flex items-center justify-between px-2 py-1.5 gap-1.5 overflow-x-auto text-xs font-mono no-scrollbar">
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={() => handleKeyClick('ESC')}
            className="h-8 px-2.5 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 active:text-white border border-slate-700 shadow-sm flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            ESC
          </button>
          <button
            onClick={() => handleKeyClick('TAB')}
            className="h-8 px-2.5 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 active:text-white border border-slate-700 shadow-sm flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            TAB
          </button>
          <button
            onClick={() => setCtrlActive(!ctrlActive)}
            className={`h-8 px-2.5 rounded border shadow-sm transition-all flex items-center justify-center font-bold active:scale-95 ${
              ctrlActive
                ? 'bg-cyan-500 text-black border-cyan-400 shadow-cyan-500/20 shadow-md'
                : 'bg-slate-800 text-slate-300 border-slate-700'
            }`}
          >
            CTRL
          </button>
          <button
            onClick={() => handleKeyClick('^C')}
            className="h-8 px-2 rounded bg-slate-800 active:bg-rose-600 text-rose-300 active:text-white border border-slate-700 shadow-sm font-bold flex items-center justify-center active:scale-95 transition-transform"
          >
            ^C
          </button>
          <button
            onClick={() => handleKeyClick('^D')}
            className="h-8 px-2 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 active:text-white border border-slate-700 shadow-sm flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            ^D
          </button>
          <button
            onClick={() => handleKeyClick('^Z')}
            className="h-8 px-2 rounded bg-slate-800 active:bg-amber-600 text-amber-300 active:text-white border border-slate-700 shadow-sm flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            ^Z
          </button>
          <button
            onClick={() => handleKeyClick('^L')}
            className="h-8 px-2 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 active:text-white border border-slate-700 shadow-sm flex items-center justify-center font-bold active:scale-95 transition-transform"
            title="清屏 (Ctrl+L)"
          >
            ^L
          </button>
        </div>

        {/* Directional Pad */}
        <div className="flex items-center gap-1 shrink-0 px-1 border-x border-slate-800">
          <button
            onClick={() => handleKeyClick('LEFT')}
            className="h-8 w-8 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 border border-slate-700 flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            ←
          </button>
          <button
            onClick={() => handleKeyClick('UP')}
            className="h-8 w-8 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 border border-slate-700 flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            ↑
          </button>
          <button
            onClick={() => handleKeyClick('DOWN')}
            className="h-8 w-8 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 border border-slate-700 flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            ↓
          </button>
          <button
            onClick={() => handleKeyClick('RIGHT')}
            className="h-8 w-8 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 border border-slate-700 flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            →
          </button>
        </div>

        {/* Tools: Paste, Zoom, and Prompt Drawer */}
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={handlePaste}
            title="从剪贴板粘贴到终端"
            className="h-8 px-2 rounded bg-slate-800 hover:bg-slate-700 active:bg-cyan-600 text-cyan-300 active:text-white border border-slate-700 shadow-sm flex items-center gap-1 font-medium active:scale-95 transition-transform"
          >
            <Clipboard className="w-3.5 h-3.5" />
            <span className="text-[11px]">粘贴</span>
          </button>

          {onZoomOut && onZoomIn && (
            <div className="flex items-center rounded bg-slate-800 border border-slate-700 overflow-hidden">
              <button
                onClick={onZoomOut}
                title="缩小字体"
                className="h-8 px-2 hover:bg-slate-700 text-slate-400 hover:text-white flex items-center justify-center text-[10px] font-bold"
              >
                A-
              </button>
              <span className="w-px h-4 bg-slate-700" />
              <button
                onClick={onZoomIn}
                title="放大字体"
                className="h-8 px-2 hover:bg-slate-700 text-slate-400 hover:text-white flex items-center justify-center text-[10px] font-bold"
              >
                A+
              </button>
            </div>
          )}

          <button
            onClick={() => handleKeyClick('/')}
            className="h-8 w-8 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 border border-slate-700 flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            /
          </button>
          <button
            onClick={() => handleKeyClick('-')}
            className="h-8 w-8 rounded bg-slate-800 active:bg-cyan-600 text-slate-300 border border-slate-700 flex items-center justify-center font-bold active:scale-95 transition-transform"
          >
            -
          </button>

          {/* Quick Prompt Toggle Button */}
          <button
            onClick={() => setIsDrawerOpen(!isDrawerOpen)}
            className={`h-8 flex items-center gap-1 px-2.5 rounded border shadow-sm transition-all active:scale-95 ${
              isDrawerOpen
                ? 'bg-cyan-600 text-white border-cyan-400 font-semibold'
                : 'bg-indigo-950/60 text-indigo-300 border-indigo-800/60 hover:bg-indigo-900/60'
            }`}
          >
            <Sparkles className="w-3.5 h-3.5" />
            <span>输入框</span>
            {isDrawerOpen ? <ChevronDown className="w-3 h-3" /> : <ChevronUp className="w-3 h-3" />}
          </button>
        </div>
      </div>
    </div>
  );
}
