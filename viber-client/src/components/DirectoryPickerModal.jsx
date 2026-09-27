import React, { useState, useEffect } from 'react';
import { 
  Folder, 
  FolderGit2, 
  ChevronRight, 
  ArrowUp, 
  Home, 
  HardDrive, 
  Plus, 
  Check, 
  X, 
  RefreshCw,
  FolderPlus
} from 'lucide-react';

export default function DirectoryPickerModal({
  isOpen,
  onClose,
  connection,
  initialPath,
  onSelectDirectory,
}) {
  const [currentPath, setCurrentPath] = useState(initialPath || '/workspace');
  const [parentPath, setParentPath] = useState(null);
  const [folders, setFolders] = useState([]);
  const [drives, setDrives] = useState([]);
  const [homePath, setHomePath] = useState('');
  const [workspacePath, setWorkspacePath] = useState('');
  const [loading, setLoading] = useState(false);
  const [newFolderName, setNewFolderName] = useState('');
  const [isCreatingFolder, setIsCreatingFolder] = useState(false);

  // Request directory listing from remote host
  const loadDirectory = (targetPath) => {
    if (!connection) return;
    setLoading(true);
    const reqId = 'dir_' + Math.random().toString(36).substring(2, 8);

    const cleanup = connection.on('dir_list', (msg) => {
      if (msg.req_id === reqId || !msg.req_id) {
        cleanup();
        setLoading(false);
        if (msg.data) {
          setCurrentPath(msg.data.current_path);
          setParentPath(msg.data.parent_path);
          setFolders(msg.data.folders || []);
          setDrives(msg.data.drives || []);
          setHomePath(msg.data.home || '');
          setWorkspacePath(msg.data.workspace || '');
        }
      }
    });

    connection.listDirectory(targetPath, reqId);
  };

  useEffect(() => {
    if (isOpen) {
      loadDirectory(initialPath || currentPath);
    }
  }, [isOpen]);

  const handleNavigate = (path) => {
    loadDirectory(path);
  };

  const handleCreateFolder = () => {
    if (!newFolderName.trim() || !connection) return;
    const fullNewPath = currentPath.endsWith('/') || currentPath.endsWith('\\')
      ? currentPath + newFolderName.trim()
      : currentPath + '/' + newFolderName.trim();

    const cleanup = connection.on('dir_created', (msg) => {
      cleanup();
      setIsCreatingFolder(false);
      setNewFolderName('');
      loadDirectory(currentPath);
    });

    connection.createDirectory(fullNewPath);
  };

  const handleConfirm = () => {
    onSelectDirectory(currentPath);
    onClose();
  };

  if (!isOpen) return null;

  // Split path for breadcrumb navigation
  const isWindows = currentPath.includes('\\') || /^[A-Z]:/i.test(currentPath);
  const separator = isWindows ? '\\' : '/';
  const pathParts = currentPath.split(/[/\\]/).filter(Boolean);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-2.5 sm:p-4 bg-black/75 backdrop-blur-sm animate-fade-in">
      <div className="w-full max-w-xl rounded-2xl border border-slate-800 bg-[#0d1424] shadow-2xl flex flex-col max-h-[92dvh] text-slate-200 overflow-hidden">
        {/* Modal Header */}
        <div className="p-4 border-b border-slate-800 flex items-center justify-between bg-[#0a0f1d]">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-cyan-500/20 text-cyan-400 flex items-center justify-center border border-cyan-500/30">
              <Folder className="w-4 h-4 fill-cyan-400/20" />
            </div>
            <div>
              <h3 className="font-semibold text-sm text-slate-100">远程主机目录选择器</h3>
              <p className="text-xs text-slate-500">浏览并选取目标机上的工作区或项目目录</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-slate-200 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Quick Jump Shortcuts */}
        <div className="px-4 py-2.5 bg-slate-900/60 border-b border-slate-800/80 flex items-center gap-2 overflow-x-auto no-scrollbar text-xs">
          <span className="text-slate-500 text-[11px] font-mono shrink-0">快速直达:</span>
          {workspacePath && (
            <button
              onClick={() => handleNavigate(workspacePath)}
              className="flex items-center gap-1 px-2.5 py-1 rounded-md bg-slate-800 hover:bg-cyan-950/40 text-slate-300 hover:text-cyan-300 border border-slate-700/80 shrink-0 transition-colors"
            >
              <FolderGit2 className="w-3.5 h-3.5 text-cyan-400" />
              <span>工作区 ({workspacePath})</span>
            </button>
          )}
          {homePath && (
            <button
              onClick={() => handleNavigate(homePath)}
              className="flex items-center gap-1 px-2.5 py-1 rounded-md bg-slate-800 hover:bg-cyan-950/40 text-slate-300 hover:text-cyan-300 border border-slate-700/80 shrink-0 transition-colors"
            >
              <Home className="w-3.5 h-3.5 text-purple-400" />
              <span>用户家目录 (~)</span>
            </button>
          )}
          {drives?.map((drive) => (
            <button
              key={drive}
              onClick={() => handleNavigate(drive + '\\')}
              className="flex items-center gap-1 px-2.5 py-1 rounded-md bg-slate-800 hover:bg-cyan-950/40 text-slate-300 hover:text-cyan-300 border border-slate-700/80 shrink-0"
            >
              <HardDrive className="w-3.5 h-3.5 text-emerald-400" />
              <span>{drive}</span>
            </button>
          ))}
        </div>

        {/* Breadcrumb Path Bar */}
        <div className="px-4 py-2 bg-[#090d16] border-b border-slate-800 flex items-center gap-1.5 overflow-x-auto no-scrollbar font-mono text-xs text-slate-400">
          <button
            onClick={() => handleNavigate(isWindows ? 'C:\\' : '/')}
            className="hover:text-cyan-400 transition-colors shrink-0"
          >
            {isWindows ? '此电脑' : '根目录 /'}
          </button>
          {pathParts.map((part, index) => {
            const partPath = (isWindows ? '' : '/') + pathParts.slice(0, index + 1).join(separator);
            const isLast = index === pathParts.length - 1;
            return (
              <React.Fragment key={index}>
                <ChevronRight className="w-3 h-3 text-slate-600 shrink-0" />
                <button
                  onClick={() => handleNavigate(partPath)}
                  className={`shrink-0 transition-colors ${
                    isLast ? 'text-cyan-300 font-semibold' : 'hover:text-slate-200'
                  }`}
                >
                  {part}
                </button>
              </React.Fragment>
            );
          })}
          {loading && <RefreshCw className="w-3 h-3 animate-spin text-cyan-400 ml-auto shrink-0" />}
        </div>

        {/* Folder List Content */}
        <div className="flex-1 overflow-y-auto p-3 space-y-1 min-h-[220px]">
          {/* Parent Directory Link */}
          {parentPath && (
            <div
              onClick={() => handleNavigate(parentPath)}
              className="flex items-center gap-2.5 px-3 py-2 rounded-xl text-xs text-slate-400 hover:text-white hover:bg-slate-800/60 cursor-pointer border border-transparent hover:border-slate-700 transition-all font-mono"
            >
              <div className="w-6 h-6 rounded-lg bg-slate-800 flex items-center justify-center text-slate-400">
                <ArrowUp className="w-3.5 h-3.5" />
              </div>
              <span>.. (返回上一级目录)</span>
            </div>
          )}

          {folders.length === 0 && !loading && (
            <div className="py-12 text-center text-slate-500 text-xs font-mono">
              当前目录下没有子文件夹
            </div>
          )}

          {folders.map((folder) => (
            <div
              key={folder.path}
              onClick={() => handleNavigate(folder.path)}
              className="flex items-center justify-between px-3 py-2 rounded-xl text-xs hover:bg-slate-800/80 cursor-pointer border border-transparent hover:border-cyan-500/30 transition-all group"
            >
              <div className="flex items-center gap-2.5 truncate">
                <div className={`w-7 h-7 rounded-lg flex items-center justify-center shrink-0 ${
                  folder.is_git ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30' : 'bg-slate-800 text-cyan-400'
                }`}>
                  {folder.is_git ? <FolderGit2 className="w-4 h-4" /> : <Folder className="w-4 h-4 fill-cyan-400/20" />}
                </div>
                <span className="text-slate-200 group-hover:text-cyan-300 font-mono truncate font-medium">
                  {folder.name}
                </span>
              </div>

              {folder.is_git && (
                <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-emerald-950/40 text-emerald-400 border border-emerald-800/40 shrink-0">
                  Git 仓库
                </span>
              )}
            </div>
          ))}
        </div>

        {/* Create Folder Drawer */}
        {isCreatingFolder ? (
          <div className="p-3 bg-slate-900 border-t border-slate-800 flex items-center gap-2">
            <FolderPlus className="w-4 h-4 text-cyan-400 shrink-0" />
            <input
              type="text"
              value={newFolderName}
              onChange={(e) => setNewFolderName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleCreateFolder();
              }}
              placeholder="输入新文件夹名称..."
              autoFocus
              className="flex-1 bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-cyan-500 font-mono"
            />
            <button
              onClick={handleCreateFolder}
              className="px-3 py-1.5 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-medium"
            >
              创建
            </button>
            <button
              onClick={() => setIsCreatingFolder(false)}
              className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 text-xs"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        ) : null}

        {/* Footer with Selected Path & Confirm */}
        <div className="p-4 border-t border-slate-800 bg-[#0a0f1d] flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center justify-between sm:justify-start gap-2 overflow-hidden">
            <span className="text-[11px] text-slate-400 shrink-0">当前选中:</span>
            <span className="text-xs font-mono text-cyan-300 bg-slate-900 px-2 py-1 rounded border border-slate-800 truncate">
              {currentPath}
            </span>
          </div>

          <div className="flex items-center gap-2 justify-end">
            {!isCreatingFolder && (
              <button
                onClick={() => setIsCreatingFolder(true)}
                className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-white text-xs border border-slate-700"
              >
                <Plus className="w-3.5 h-3.5" />
                <span>新建文件夹</span>
              </button>
            )}
            <button
              onClick={handleConfirm}
              className="flex items-center gap-1.5 px-4 py-1.5 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white text-xs font-semibold shadow-lg shadow-cyan-900/30 active:scale-95 transition-all"
            >
              <Check className="w-3.5 h-3.5" />
              <span>确定选择此目录</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
