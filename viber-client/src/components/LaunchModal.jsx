import React, { useState, useEffect } from 'react';
import { 
  X, 
  Play, 
  FolderGit2, 
  Terminal, 
  Plus, 
  FolderSearch, 
  Layers, 
  BookmarkPlus, 
  Trash2, 
  Check, 
  Container, 
  Sparkles,
  Command,
  FileCode2
} from 'lucide-react';
import DirectoryPickerModal from './DirectoryPickerModal';

export default function LaunchModal({
  isOpen,
  onClose,
  profiles = [],
  onLaunch,
  onSaveProfile,
  onDeleteProfile,
  initialProfile,
  connection,
  defaultFolder,
}) {
  const [selectedProfileId, setSelectedProfileId] = useState('custom');
  const [customName, setCustomName] = useState('');
  const [cwd, setCwd] = useState('/workspace');
  const [folder, setFolder] = useState(defaultFolder || '');
  const [extraArgs, setExtraArgs] = useState('');
  const [customCommand, setCustomCommand] = useState('bash');
  const [keepAlive, setKeepAlive] = useState(false);
  const [isDirectoryPickerOpen, setIsDirectoryPickerOpen] = useState(false);
  const [savedFeedback, setSavedFeedback] = useState(false);

  useEffect(() => {
    if (initialProfile) {
      setSelectedProfileId(initialProfile.id);
      setCustomName(initialProfile.name || '');
      setCwd(initialProfile.default_cwd || '/workspace');
      setFolder(initialProfile.folder || defaultFolder || '');
      const cmd = initialProfile.command;
      setCustomCommand(Array.isArray(cmd) ? cmd.join(' ') : (cmd || ''));
    } else if (profiles && profiles.length > 0 && selectedProfileId !== 'custom') {
      const found = profiles.find((p) => p.id === selectedProfileId);
      if (found) {
        setCustomName(found.name || '');
        setCwd(found.default_cwd || '/workspace');
        setFolder(found.folder || defaultFolder || '');
        const cmd = found.command;
        setCustomCommand(Array.isArray(cmd) ? cmd.join(' ') : (cmd || ''));
      }
    }
    if (defaultFolder && !folder) {
      setFolder(defaultFolder);
    }
  }, [initialProfile, profiles, defaultFolder]);

  const handleProfileSelect = (prof) => {
    setSelectedProfileId(prof.id);
    setCustomName(prof.name || '');
    setCwd(prof.default_cwd || '/workspace');
    setFolder(prof.folder || defaultFolder || '');
    const cmd = prof.command;
    setCustomCommand(Array.isArray(cmd) ? cmd.join(' ') : (cmd || ''));
  };

  const handleStartBlank = () => {
    setSelectedProfileId('custom');
    setCustomName('');
    setCustomCommand('bash');
    setCwd('/workspace');
  };

  const handleDeleteProfile = (e, profId) => {
    e.stopPropagation();
    if (window.confirm('确定要删除此预设配置吗？')) {
      onDeleteProfile?.(profId);
      if (selectedProfileId === profId) {
        handleStartBlank();
      }
    }
  };

  const handleInsertSnippet = (snippet) => {
    setCustomCommand((prev) => {
      const trimmed = prev.trim();
      if (!trimmed || trimmed === 'bash') {
        return snippet;
      }
      return `${trimmed}\n${snippet}`;
    });
  };

  const handleSavePreset = () => {
    const nameToSave = customName.trim() || '自定义预设';
    const cmdToSave = customCommand.trim() || 'bash';
    const profileData = {
      id: selectedProfileId === 'custom' ? undefined : selectedProfileId,
      name: nameToSave,
      command: cmdToSave,
      default_cwd: cwd.trim() || '/workspace',
      folder: folder.trim(),
      category: cmdToSave.includes('docker') ? 'docker' : 'agent',
      gradient: cmdToSave.includes('docker')
        ? 'from-sky-600 via-blue-600 to-indigo-700'
        : 'from-cyan-600 via-blue-600 to-indigo-700',
      icon: cmdToSave.includes('docker') ? 'zap' : 'bot',
    };

    onSaveProfile?.(profileData);
    setSavedFeedback(true);
    setTimeout(() => setSavedFeedback(false), 2500);
  };

  const handleLaunch = () => {
    const argsArray = extraArgs.trim() ? extraArgs.trim().split(/\s+/) : [];
    const derivedFolder = folder.trim() || (cwd.split(/[/\\]/).filter(Boolean).pop() || '默认项目');
    const finalCmd = customCommand.trim() || 'bash';
    const finalName = customName.trim() || (finalCmd.includes('docker') ? 'Docker 容器' : '终端 Agent');

    onLaunch({
      profile_id: selectedProfileId !== 'custom' ? selectedProfileId : undefined,
      name: finalName,
      command: finalCmd,
      cwd: cwd || '/workspace',
      folder: derivedFolder,
      extra_args: argsArray,
      keep_alive: keepAlive,
    });
    onClose();
  };

  const handleSaveAndLaunch = () => {
    handleSavePreset();
    handleLaunch();
  };

  if (!isOpen) return null;

  return (
    <>
      <div className="fixed inset-0 z-50 flex items-center justify-center p-2.5 sm:p-4 bg-black/80 backdrop-blur-sm animate-fade-in">
        <div className="w-full max-w-xl rounded-2xl border border-slate-800 bg-[#0c1220] shadow-2xl p-4 sm:p-6 text-slate-200 max-h-[94dvh] flex flex-col overflow-hidden">
          {/* Header */}
          <div className="flex items-center justify-between pb-3 sm:pb-4 border-b border-slate-800 shrink-0">
            <div className="flex items-center gap-2.5">
              <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-cyan-500/20 to-blue-600/20 text-cyan-400 flex items-center justify-center border border-cyan-500/30 shrink-0">
                <Play className="w-4 h-4 fill-cyan-400" />
              </div>
              <div>
                <h3 className="font-semibold text-sm sm:text-base text-slate-100 flex items-center gap-2">
                  <span>启动 Agent / Docker / 终端</span>
                </h3>
                <p className="text-[11px] sm:text-xs text-slate-400">
                  支持多指令脚本、Docker 容器与预设快速管理
                </p>
              </div>
            </div>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-slate-200 transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
          </div>

          <div className="flex-1 overflow-y-auto pr-1 my-3 space-y-4 text-xs">
            {/* Presets Management Section */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <label className="text-xs font-medium text-slate-300 flex items-center gap-1.5">
                  <Sparkles className="w-3.5 h-3.5 text-cyan-400" />
                  <span>预配置预设 (已保存 {profiles?.length || 0} 个)</span>
                </label>
                <button
                  type="button"
                  onClick={handleStartBlank}
                  className={`text-[11px] flex items-center gap-1 px-2 py-0.5 rounded-md transition-colors ${
                    selectedProfileId === 'custom'
                      ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800'
                  }`}
                >
                  <Plus className="w-3 h-3" />
                  <span>新建空白配置</span>
                </button>
              </div>

              {profiles && profiles.length > 0 ? (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 max-h-36 overflow-y-auto pr-1">
                  {profiles.map((prof) => (
                    <div
                      key={prof.id}
                      onClick={() => handleProfileSelect(prof)}
                      className={`group p-2.5 rounded-xl border text-xs cursor-pointer transition-all flex items-center justify-between gap-2 ${
                        selectedProfileId === prof.id
                          ? 'border-cyan-500 bg-cyan-950/40 text-white shadow-sm shadow-cyan-900/30'
                          : 'border-slate-800/90 bg-slate-900/60 text-slate-300 hover:border-slate-700 hover:bg-slate-900'
                      }`}
                    >
                      <div className="truncate flex-1">
                        <div className="font-medium truncate flex items-center gap-1.5">
                          {prof.command?.includes('docker') ? (
                            <Container className="w-3.5 h-3.5 text-sky-400 shrink-0" />
                          ) : (
                            <Terminal className="w-3.5 h-3.5 text-cyan-400 shrink-0" />
                          )}
                          <span className="truncate">{prof.name}</span>
                        </div>
                        <div className="text-[10px] text-slate-500 font-mono truncate mt-0.5">
                          {Array.isArray(prof.command) ? prof.command.join(' ') : prof.command}
                        </div>
                      </div>

                      {onDeleteProfile && (
                        <button
                          type="button"
                          onClick={(e) => handleDeleteProfile(e, prof.id)}
                          title="删除此预设"
                          className="opacity-60 group-hover:opacity-100 p-1 rounded-md text-slate-500 hover:text-rose-400 hover:bg-rose-500/10 transition-all shrink-0"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              ) : (
                <div className="p-3 rounded-xl border border-dashed border-slate-800 bg-slate-900/40 text-slate-400 text-center">
                  暂无保存的预设配置。在下方配置指令后，点击【保存为预设】即可永久记录！
                </div>
              )}
            </div>

            {/* Form Fields */}
            <div className="space-y-3.5 pt-1 border-t border-slate-800/80">
              {/* Name & Folder */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                <div>
                  <label className="text-slate-400 block mb-1">会话 / 预设显示名称</label>
                  <input
                    type="text"
                    value={customName}
                    onChange={(e) => setCustomName(e.target.value)}
                    placeholder="例如: Docker Agent 或 前端构建"
                    className="w-full bg-slate-900/90 border border-slate-700/80 rounded-lg px-3 py-2 text-white placeholder-slate-600 focus:outline-none focus:border-cyan-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block mb-1 flex items-center gap-1">
                    <Layers className="w-3.5 h-3.5 text-purple-400" />
                    <span>所属项目 / 分组文件夹</span>
                  </label>
                  <input
                    type="text"
                    value={folder}
                    onChange={(e) => setFolder(e.target.value)}
                    placeholder="例如: docker-agents 或 主项目"
                    className="w-full bg-slate-900/90 border border-slate-700/80 rounded-lg px-3 py-2 text-white placeholder-slate-600 focus:outline-none focus:border-cyan-500"
                  />
                </div>
              </div>

              {/* Multi-instruction Script / Command Area */}
              <div>
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1.5 mb-1.5">
                  <label className="text-slate-300 font-medium flex items-center gap-1.5">
                    <FileCode2 className="w-3.5 h-3.5 text-cyan-400" />
                    <span>执行指令 / 脚本内容 (支持多指令与 Docker)</span>
                  </label>
                  <div className="flex items-center gap-1 flex-wrap">
                    <span className="text-[10px] text-slate-500">快捷填充:</span>
                    <button
                      type="button"
                      onClick={() => handleInsertSnippet('codex --no-alt-screen')}
                      className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-cyan-300 hover:bg-slate-700 transition-colors border border-slate-700"
                    >
                      + Codex (防截断)
                    </button>
                    <button
                      type="button"
                      onClick={() => handleInsertSnippet('docker run -it --rm -v "$(pwd):/workspace" -w /workspace ubuntu bash')}
                      className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-sky-300 hover:bg-slate-700 transition-colors border border-slate-700"
                    >
                      + Docker 交互容器
                    </button>
                    <button
                      type="button"
                      onClick={() => handleInsertSnippet('docker compose up -d && docker exec -it $(docker compose ps -q | head -n1) bash')}
                      className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-blue-300 hover:bg-slate-700 transition-colors border border-slate-700"
                    >
                      + Compose 进入
                    </button>
                    <button
                      type="button"
                      onClick={() => handleInsertSnippet('exec bash')}
                      className="text-[10px] px-1.5 py-0.5 rounded bg-slate-800 text-emerald-300 hover:bg-slate-700 transition-colors border border-slate-700"
                    >
                      + 保持 Shell
                    </button>
                  </div>
                </div>

                <textarea
                  rows={4}
                  value={customCommand}
                  onChange={(e) => setCustomCommand(e.target.value)}
                  placeholder={`# 支持多指令链式执行、Docker 运行及环境预设，例如:\ncd /workspace\ndocker run -it --rm -v $(pwd):/app -w /app my-agent:latest\n# 或:\ndocker compose up -d && docker exec -it container_name bash`}
                  className="w-full bg-[#070b14] border border-slate-700/80 rounded-xl px-3 py-2 text-cyan-200 font-mono text-xs placeholder-slate-600 focus:outline-none focus:border-cyan-500 leading-relaxed shadow-inner"
                />

                <div className="mt-1 flex items-center justify-between text-[11px] text-slate-500">
                  <span>⚡ 命令将在宿主机伪终端 (PTY) 中高保真运行，完整支持 Docker -it 交互。</span>
                  <label className="flex items-center gap-1.5 cursor-pointer text-slate-400 hover:text-slate-200">
                    <input
                      type="checkbox"
                      checked={keepAlive}
                      onChange={(e) => setKeepAlive(e.target.checked)}
                      className="rounded bg-slate-800 border-slate-700 text-cyan-500 focus:ring-0"
                    />
                    <span>运行结束后保持 Shell 打开</span>
                  </label>
                </div>
              </div>

              {/* Remote Working Directory with Picker */}
              <div>
                <label className="text-slate-400 block mb-1 flex items-center justify-between">
                  <span className="flex items-center gap-1">
                    <FolderGit2 className="w-3.5 h-3.5 text-cyan-400" />
                    <span>远程目标工作目录 (CWD)</span>
                  </span>
                  <span className="text-[10px] text-slate-500">支持点击右侧按钮图形化浏览</span>
                </label>

                <div className="flex gap-2">
                  <input
                    type="text"
                    value={cwd}
                    onChange={(e) => setCwd(e.target.value)}
                    placeholder="/workspace 或项目绝对路径"
                    className="flex-1 bg-slate-900/90 border border-slate-700/80 rounded-lg px-3 py-2 text-white font-mono placeholder-slate-600 focus:outline-none focus:border-cyan-500"
                  />
                  <button
                    type="button"
                    onClick={() => setIsDirectoryPickerOpen(true)}
                    className="px-3 py-2 rounded-lg bg-cyan-950/40 hover:bg-cyan-900/50 text-cyan-300 border border-cyan-700/60 font-medium flex items-center gap-1.5 transition-colors shrink-0 shadow-sm"
                  >
                    <FolderSearch className="w-3.5 h-3.5" />
                    <span>浏览目录</span>
                  </button>
                </div>
              </div>

              {/* Extra arguments */}
              <div>
                <label className="text-slate-400 block mb-1">附加运行参数 (可选)</label>
                <input
                  type="text"
                  value={extraArgs}
                  onChange={(e) => setExtraArgs(e.target.value)}
                  placeholder="例如: --verbose 或传递给启动指令的附加标志"
                  className="w-full bg-slate-900/90 border border-slate-700/80 rounded-lg px-3 py-2 text-white font-mono placeholder-slate-600 focus:outline-none focus:border-cyan-500"
                />
              </div>
            </div>
          </div>

          {/* Footer Actions */}
          <div className="mt-3 pt-3 border-t border-slate-800 flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-2 shrink-0">
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={handleSavePreset}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-slate-800/90 hover:bg-slate-700 text-slate-200 border border-slate-700 text-xs font-medium transition-all"
              >
                {savedFeedback ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-emerald-400" />
                    <span className="text-emerald-400">预设已保存!</span>
                  </>
                ) : (
                  <>
                    <BookmarkPlus className="w-3.5 h-3.5 text-cyan-400" />
                    <span>保存当前为预设</span>
                  </>
                )}
              </button>
            </div>

            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={onClose}
                className="px-3.5 py-2 rounded-lg text-slate-400 hover:text-slate-200 text-xs font-medium transition-colors"
              >
                取消
              </button>
              <button
                type="button"
                onClick={handleSaveAndLaunch}
                className="px-3.5 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-cyan-300 border border-cyan-800/50 text-xs font-medium transition-all hidden sm:flex items-center gap-1"
              >
                <BookmarkPlus className="w-3.5 h-3.5" />
                <span>保存并启动</span>
              </button>
              <button
                type="button"
                onClick={handleLaunch}
                className="px-4 py-2 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-medium text-xs shadow-lg shadow-cyan-900/30 flex items-center justify-center gap-1.5 active:scale-95 transition-all"
              >
                <Play className="w-3.5 h-3.5 fill-white" />
                <span>立即启动</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Embedded Remote Directory Picker */}
      <DirectoryPickerModal
        isOpen={isDirectoryPickerOpen}
        onClose={() => setIsDirectoryPickerOpen(false)}
        connection={connection}
        initialPath={cwd}
        onSelectDirectory={(selectedPath) => {
          setCwd(selectedPath);
          if (!folder) {
            const parts = selectedPath.split(/[/\\]/).filter(Boolean);
            if (parts.length > 0) setFolder(parts[parts.length - 1]);
          }
        }}
      />
    </>
  );
}
