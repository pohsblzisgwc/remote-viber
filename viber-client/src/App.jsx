import { loadSessionConfig, parsePairingBundle } from './services/pairing.js';
import React, { useState, useEffect, useRef } from 'react';
import TopBar from './components/TopBar';
import Dashboard from './components/Dashboard';
import TerminalView from './components/TerminalView';
import MosaicTerminalGrid from './components/MosaicTerminalGrid';
import SessionTabs from './components/SessionTabs';
import LaunchModal from './components/LaunchModal';
import PairingModal from './components/PairingModal';
import { ViberConnection } from './services/viber_connection';

const DEFAULT_CONFIG_KEY = 'viber_host_config_v2';

export default function App() {
  const [hostConfig, setHostConfig] = useState(() => loadSessionConfig(DEFAULT_CONFIG_KEY));

  const [activeView, setActiveView] = useState('dashboard'); // 'dashboard' | 'terminal'
  const [connectionState, setConnectionState] = useState('disconnected');
  const [connectionMode, setConnectionMode] = useState('unknown');
  const [pingMs, setPingMs] = useState(0);

  const [systemStats, setSystemStats] = useState(null);
  const [profiles, setProfiles] = useState([]);
  const [sessions, setSessions] = useState([]);
  const [activeSessionId, setActiveSessionId] = useState(null);

  const [isLaunchModalOpen, setIsLaunchModalOpen] = useState(false);
  const [isPairingModalOpen, setIsPairingModalOpen] = useState(false);
  const [selectedProfileForLaunch, setSelectedProfileForLaunch] = useState(null);
  const [targetFolderForLaunch, setTargetFolderForLaunch] = useState('');

  const connectionRef = useRef(null);

  // Pairing is an explicit user action; URLs and HTTP metadata never confer trust.
  useEffect(() => {
    const url = new URL(window.location.href);
    for (const name of ['token', 'hostId', 'host_id']) url.searchParams.delete(name);
    if (url.href !== window.location.href) window.history.replaceState(null, '', url.href);

    try {
      const autoCode = sessionStorage.getItem('viber_local_pairing_code');
      if (autoCode && (!hostConfig.hostPub || !hostConfig.token)) {
        parsePairingBundle(autoCode).then((cfg) => {
          handleSaveConfig(cfg);
          setIsPairingModalOpen(false);
        }).catch(() => {
          setIsPairingModalOpen(true);
        });
      } else if (!hostConfig.hostPub || !hostConfig.token) {
        setIsPairingModalOpen(true);
      }
    } catch (_) {
      if (!hostConfig.hostPub || !hostConfig.token) setIsPairingModalOpen(true);
    }

    window.viber_import_pairing = (code) => {
      if (!code) return;
      parsePairingBundle(code).then((cfg) => {
        handleSaveConfig(cfg);
        setIsPairingModalOpen(false);
      }).catch(console.error);
    };

    return () => {
      delete window.viber_import_pairing;
    };
  }, []);

  // Initialize or re-create connection when hostConfig changes
  useEffect(() => {
    if (connectionRef.current) {
      connectionRef.current.disconnect();
    }

    const conn = new ViberConnection(hostConfig);
    connectionRef.current = conn;

    conn.on('status_change', ({ status, mode }) => {
      setConnectionState(status);
      setConnectionMode(mode);
    });



    conn.on('error', (err) => {
      console.warn('RemoteViber Connection Error:', err);
      if (typeof err === 'string' && (err.includes('token') || err.includes('Authentication') || err.includes('Handshake'))) {
        setIsPairingModalOpen(true);
      }
    });

    conn.on('ping', (ms) => {
      setPingMs(ms);
    });

    conn.on('stats', (data) => {
      setSystemStats(data);
      if (data.sessions) {
        setSessions(data.sessions);
      }
    });

    conn.on('profiles', (profs) => {
      setProfiles(profs);
    });

    conn.on('agent_error', (errMsg) => {
      console.error('RemoteViber Agent Error:', errMsg);
      alert(`启动失败: ${errMsg}`);
    });

    conn.on('agent_launched', (newSession) => {
      setSessions((prev) => {
        const filtered = prev.filter((s) => s.session_id !== newSession.session_id);
        return [...filtered, newSession];
      });
      setActiveSessionId(newSession.session_id);
      setActiveView('terminal');
    });

    conn.on('agent_terminated', ({ session_id }) => {
      setSessions((prev) =>
        prev.map((s) => (s.session_id === session_id ? { ...s, status: 'stopped' } : s))
      );
    });

    conn.on('session_deleted', ({ session_id }) => {
      setSessions((prev) => prev.filter((s) => s.session_id !== session_id));
      setActiveSessionId((curr) => {
        if (curr === session_id) {
          return null;
        }
        return curr;
      });
    });

    conn.on('session_restarted', (newSession) => {
      setSessions((prev) => {
        const filtered = prev.filter((s) => s.session_id !== newSession.session_id);
        return [...filtered, newSession];
      });
      setActiveSessionId(newSession.session_id);
      setActiveView('terminal');
    });

    conn.on('session_folder_updated', ({ session_id, folder }) => {
      setSessions((prev) =>
        prev.map((s) => (s.session_id === session_id ? { ...s, folder } : s))
      );
    });

    conn.connect();

    return () => {
      conn.disconnect();
    };
  }, [hostConfig]);

  // Periodic stats polling when connected
  useEffect(() => {
    if (connectionState !== 'connected') return;

    const interval = setInterval(() => {
      if (connectionRef.current) {
        connectionRef.current.getStats();
      }
    }, 3000);

    return () => clearInterval(interval);
  }, [connectionState]);

  // Handle 1-click launch from card
  const handleQuickLaunch = (profile) => {
    if (connectionRef.current) {
      connectionRef.current.launchAgent({
        profile_id: profile.id,
        name: profile.name,
        cwd: profile.default_cwd || '',
      });
    }
  };

  // Direct Quick Terminal without needing an agent
  const handleQuickTerminal = (cwd = '', folder = '') => {
    if (connectionRef.current) {
      connectionRef.current.launchTerminal({
        cwd: cwd || '',
        folder: folder,
      });
    }
  };

  // Integration with native desktop client buttons and hotkeys
  useEffect(() => {
    const onQuickTerm = () => handleQuickTerminal();
    const onSwitchView = (e) => {
      if (e.detail) setActiveView(e.detail);
    };
    const onOpenPairing = () => setIsPairingModalOpen(true);
    const onOpenLaunch = () => setIsLaunchModalOpen(true);

    window.addEventListener('viber:quickTerminal', onQuickTerm);
    window.addEventListener('viber:switchView', onSwitchView);
    window.addEventListener('viber:openPairing', onOpenPairing);
    window.addEventListener('viber:openLaunch', onOpenLaunch);

    window.viber = {
      quickTerminal: handleQuickTerminal,
      switchView: (v) => setActiveView(v),
      openPairing: () => setIsPairingModalOpen(true),
      openLaunch: () => setIsLaunchModalOpen(true),
    };

    return () => {
      window.removeEventListener('viber:quickTerminal', onQuickTerm);
      window.removeEventListener('viber:switchView', onSwitchView);
      window.removeEventListener('viber:openPairing', onOpenPairing);
      window.removeEventListener('viber:openLaunch', onOpenLaunch);
      delete window.viber;
    };
  }, [sessions, activeSessionId]);

  // Handle customized launch
  const handleConfigureLaunch = (config) => {
    if (config?.default_cwd || config?.folder) {
      setSelectedProfileForLaunch(null);
      setTargetFolderForLaunch(config.folder || '');
    } else {
      setSelectedProfileForLaunch(config);
      setTargetFolderForLaunch('');
    }
    setIsLaunchModalOpen(true);
  };

  const handleExecuteLaunch = (options) => {
    if (connectionRef.current) {
      connectionRef.current.launchAgent(options);
    }
  };

  const handleSaveProfile = (profile) => {
    if (connectionRef.current) {
      connectionRef.current.saveProfile(profile);
    }
  };

  const handleDeleteProfile = (profileId) => {
    if (connectionRef.current) {
      connectionRef.current.deleteProfile(profileId);
    }
  };

  const handleUpdateSessionFolder = (sessionId, folder) => {
    if (connectionRef.current) {
      connectionRef.current.updateSessionFolder(sessionId, folder);
      setSessions((prev) =>
        prev.map((s) => (s.session_id === sessionId ? { ...s, folder } : s))
      );
    }
  };

  // Session switching
  const handleAttachSession = (sessionId) => {
    setActiveSessionId(sessionId);
    setActiveView('terminal');
  };

  const handleTerminateSession = (sessionId) => {
    if (connectionRef.current) {
      connectionRef.current.terminateAgent(sessionId);
    }
    setSessions((prev) =>
      prev.map((s) => (s.session_id === sessionId ? { ...s, status: 'stopped' } : s))
    );
  };

  const handleDeleteSession = (sessionId) => {
    if (connectionRef.current) {
      connectionRef.current.deleteSession(sessionId);
    }
    setSessions((prev) => {
      const next = prev.filter((s) => s.session_id !== sessionId);
      if (activeSessionId === sessionId) {
        if (next.length > 0) {
          setActiveSessionId(next[0].session_id);
        } else {
          setActiveSessionId(null);
          setActiveView('dashboard');
        }
      }
      return next;
    });
  };

  const handleRestartSession = (sessionId) => {
    if (connectionRef.current) {
      connectionRef.current.restartSession(sessionId);
    }
  };

  const handleCloseTab = (session) => {
    const sessionId = typeof session === 'object' ? session.session_id : session;
    const sess = sessions.find((s) => s.session_id === sessionId);
    if (!sess || sess.status === 'stopped') {
      handleDeleteSession(sessionId);
    } else {
      if (window.confirm(`确定要关闭并终止终端【${sess.name}】吗？`)) {
        handleTerminateSession(sessionId);
        handleDeleteSession(sessionId);
      }
    }
  };

  const handleSaveConfig = (newConfig) => {
    setHostConfig(newConfig);
    try {
      sessionStorage.setItem(DEFAULT_CONFIG_KEY, JSON.stringify(newConfig));
    } catch (e) {}
  };

  const activeSession = sessions.find((s) => s.session_id === activeSessionId) || sessions[0];

  return (
    <div className="h-[100dvh] min-h-[100dvh] w-full flex flex-col bg-[#090d16] text-slate-100 overflow-hidden font-sans">
      {/* Universal TopBar */}
      <TopBar
        connectionState={connectionState}
        connectionMode={connectionMode}
        pingMs={pingMs}
        activeView={activeView}
        setActiveView={setActiveView}
        onOpenLaunchModal={() => {
          setSelectedProfileForLaunch(null);
          setTargetFolderForLaunch('');
          setIsLaunchModalOpen(true);
        }}
        onQuickTerminal={() => handleQuickTerminal()}
        onOpenPairingModal={() => setIsPairingModalOpen(true)}
        hostConfig={hostConfig}
        systemStats={systemStats}
        activeSessionsCount={sessions.filter((s) => s.status !== 'stopped').length}
      />

      {/* Main View Area */}
      <main className="flex-1 flex flex-col overflow-hidden relative">
        {activeView === 'dashboard' ? (
          <Dashboard
            profiles={profiles}
            sessions={sessions}
            systemStats={systemStats}
            connectionState={connectionState}
            onQuickLaunch={handleQuickLaunch}
            onConfigureLaunch={handleConfigureLaunch}
            onQuickTerminal={handleQuickTerminal}
            onAttachSession={handleAttachSession}
            onTerminateSession={handleTerminateSession}
            onRestartSession={handleRestartSession}
            onDeleteSession={handleDeleteSession}
            onUpdateSessionFolder={handleUpdateSessionFolder}
            onOpenNewProfileModal={() => {
              setSelectedProfileForLaunch(null);
              setTargetFolderForLaunch('');
              setIsLaunchModalOpen(true);
            }}
            onDeleteProfile={handleDeleteProfile}
            onRefresh={() => {
              if (connectionRef.current) {
                connectionRef.current.getStats();
                connectionRef.current.listProfiles();
              }
            }}
          />
        ) : (
          <div className="flex-1 flex flex-col overflow-hidden relative">
            <SessionTabs
              sessions={sessions}
              activeSessionId={activeSessionId || activeSession?.session_id}
              onSelectSession={(id) => {
                setActiveSessionId(id);
                setActiveView('terminal');
              }}
              onCloseSession={handleCloseTab}
              onNewSession={() => handleQuickTerminal()}
              onSwitchToDashboard={() => setActiveView('dashboard')}
              onRestartSession={handleRestartSession}
              onTerminateSession={handleTerminateSession}
              onDeleteSession={handleDeleteSession}
            />
            <MosaicTerminalGrid
              sessions={sessions}
              activeSessionId={activeSessionId || activeSession?.session_id}
              onSelectSession={(id) => setActiveSessionId(id)}
              connection={connectionRef.current}
              connectionState={connectionState}
              onSwitchToDashboard={() => setActiveView('dashboard')}
              onNewTerminal={() => handleQuickTerminal()}
              onTerminateSession={handleTerminateSession}
              onRestartSession={handleRestartSession}
              onDeleteSession={handleDeleteSession}
            />
          </div>
        )}
      </main>

      {/* Modals */}
      <LaunchModal
        isOpen={isLaunchModalOpen}
        onClose={() => setIsLaunchModalOpen(false)}
        profiles={profiles}
        onLaunch={handleExecuteLaunch}
        onSaveProfile={handleSaveProfile}
        onDeleteProfile={handleDeleteProfile}
        initialProfile={selectedProfileForLaunch}
        defaultFolder={targetFolderForLaunch}
        connection={connectionRef.current}
      />

      <PairingModal
        isOpen={isPairingModalOpen}
        onClose={() => setIsPairingModalOpen(false)}
        currentConfig={hostConfig}
        onSaveConfig={handleSaveConfig}
      />
    </div>
  );
}
