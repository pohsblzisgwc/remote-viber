/**
 * RemoteViber Connection & Session Protocol Client
 * Handles connection fallbacks (Tailscale Direct -> LAN -> Relay),
 * E2EE frame encryption/decryption, heartbeat ping measurement, and seamless auto-recovery.
 */

import { ClientCryptoManager } from '../crypto/e2ee';

export class ViberConnection {
  constructor(config = {}) {
    this.config = config; // { hostId, hostName, tailscaleIps, lanIps, directPort, relayUrl, token, hostPub }
    this.crypto = new ClientCryptoManager();
    this.ws = null;
    this.status = 'disconnected'; // 'disconnected' | 'connecting' | 'handshake' | 'connected' | 'reconnecting'
    this.connectionMode = 'unknown'; // 'tailscale' | 'lan' | 'relay' | 'localhost'
    this.pingMs = 0;
    this.lastPingTs = 0;
    this.pingTimer = null;
    this.listeners = new Map();
    this.reconnectTimer = null;
    this.shouldReconnect = true;
    this.activeSessionId = null;
    this.lastReceivedSeq = 0;
  }

  on(event, callback) {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event).add(callback);
    return () => this.listeners.get(event)?.delete(callback);
  }

  emit(event, ...args) {
    this.listeners.get(event)?.forEach((cb) => {
      try {
        cb(...args);
      } catch (e) {
        console.error(`Error in event listener for ${event}:`, e);
      }
    });
  }

  async connect() {
    this.shouldReconnect = true;
    this._setStatus('connecting');

    // Build ordered list of candidate URLs
    const candidates = this._buildCandidateUrls();
    let connected = false;

    for (const candidate of candidates) {
      try {
        await this._attemptSocket(candidate.url, candidate.mode);
        connected = true;
        break;
      } catch (err) {
        console.warn(`Failed to connect via ${candidate.mode} (${candidate.url}):`, err.message);
      }
    }

    if (!connected) {
      if (this.status !== 'error') {
        this._setStatus('disconnected');
      }
      if (this.shouldReconnect) {
        this._scheduleReconnect();
      }
    }
  }

  _buildCandidateUrls() {
    const defaultPort = 8765;
    const port = this.config.directPort || (typeof window !== 'undefined' && window.location.port ? parseInt(window.location.port) : defaultPort);
    const list = [];
    const seen = new Set();

    const formatHost = (h) => {
      if (!h) return '';
      return (h.includes(':') && !h.startsWith('[')) ? `[${h}]` : h;
    };

    const addCandidate = (url, mode) => {
      if (!url || seen.has(url)) return;
      seen.add(url);
      list.push({ url, mode });
    };

    // 1. Current page origin (highest priority if loaded via web app)
    if (typeof window !== 'undefined' && window.location && window.location.hostname) {
      const locHost = window.location.hostname;
      const wsProto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const locPort = window.location.port || port;
      const isTs = locHost.startsWith('100.') || locHost.includes('.ts.net') || locHost.includes('tailscale') || locHost.toLowerCase().includes('fd7a:');
      const isLocal = locHost === 'localhost' || locHost === '127.0.0.1' || locHost === '[::1]';
      addCandidate(`${wsProto}//${formatHost(locHost)}:${locPort}/ws`, isTs ? 'tailscale' : (isLocal ? 'localhost' : 'lan'));
    }

    // 2. Explicit Tailscale candidates
    if (this.config.tailscaleIps && this.config.tailscaleIps.length > 0) {
      for (const ip of this.config.tailscaleIps) {
        if (!ip || ip.toLowerCase().startsWith('fe80:')) continue;
        addCandidate(`ws://${formatHost(ip)}:${port}/ws`, 'tailscale');
      }
    }

    // 3. LAN candidates
    if (this.config.lanIps && this.config.lanIps.length > 0) {
      for (const ip of this.config.lanIps) {
        if (!ip || ip.toLowerCase().startsWith('fe80:')) continue;
        addCandidate(`ws://${formatHost(ip)}:${port}/ws`, 'lan');
      }
    }

    // 4. Localhost fallback
    addCandidate(`ws://127.0.0.1:${port}/ws`, 'localhost');

    // 5. Central Relay Server fallback
    if (this.config.relayUrl && this.config.hostId) {
      const relayWs = this.config.relayUrl.replace(/^http/, 'ws');
      addCandidate(`${relayWs}/connect/client?host_id=${encodeURIComponent(this.config.hostId)}`, 'relay');
    }

    return list;
  }

  _attemptSocket(url, mode) {
    return new Promise((resolve, reject) => {
      let resolved = false;
      let socket = null;
      try {
        socket = new WebSocket(url);
      } catch (err) {
        return reject(err);
      }

      const timeout = setTimeout(() => {
        if (!resolved) {
          resolved = true;
          cleanupListeners();
          try { socket.close(); } catch (e) {}
          reject(new Error(`连接超时: ${url}`));
        }
      }, 5000);

      const cleanupListeners = () => {
        this.listeners.get('_handshake_ok')?.delete(onHandshakeOk);
        this.listeners.get('_handshake_err')?.delete(onHandshakeErr);
      };

      const onHandshakeOk = () => {
        if (!resolved) {
          resolved = true;
          clearTimeout(timeout);
          cleanupListeners();
          resolve();
        }
      };

      const onHandshakeErr = (err) => {
        if (!resolved) {
          resolved = true;
          clearTimeout(timeout);
          cleanupListeners();
          reject(new Error(err || '握手失败'));
        }
      };

      this.on('_handshake_ok', onHandshakeOk);
      this.on('_handshake_err', onHandshakeErr);

      socket.onopen = async () => {
        this.ws = socket;
        this.connectionMode = mode;
        this._setupSocketListeners();
        try {
          await this._performHandshake();
        } catch (e) {
          if (!resolved) {
            resolved = true;
            clearTimeout(timeout);
            cleanupListeners();
            socket.close();
            reject(e);
          }
        }
      };

      socket.onerror = (e) => {
        if (!resolved) {
          resolved = true;
          clearTimeout(timeout);
          cleanupListeners();
          reject(new Error(`WebSocket connection failed`));
        }
      };

      socket.onclose = () => {
        if (!resolved) {
          resolved = true;
          clearTimeout(timeout);
          cleanupListeners();
          reject(new Error(`连接在完成握手前已关闭`));
        }
      };
    });
  }

  async _performHandshake() {
    this._setStatus('handshake');
    let clientPub = null;
    try {
      clientPub = await this.crypto.initialize();
    } catch (e) {
      console.warn('[Viber] WebCrypto unavailable in browser context:', e.message);
    }

    const hello = {
      type: 'HELLO',
      client_id: 'client-' + Math.random().toString(36).substring(2, 8),
      client_pub: clientPub || '',
      e2ee: !!clientPub,
      token: this.config.token || '',
    };

    this.ws.send(JSON.stringify(hello));
  }

  _setupSocketListeners() {
    this.ws.onmessage = async (event) => {
      try {
        const raw = event.data;
        const msg = JSON.parse(raw);

        // Pre-handshake WELCOME
        if (msg.type === 'WELCOME') {
          if (msg.host_pub && this.crypto.supported && msg.e2ee !== false) {
            await this.crypto.establishSession(msg.host_pub);
          }
          this._setStatus('connected');
          this._startHeartbeat();
          if (msg.token) {
            this.config.token = msg.token;
          }
          this.emit('ready', { hostPub: msg.host_pub, fingerprint: msg.fingerprint, token: msg.token, e2ee: msg.e2ee !== false });
          this.emit('_handshake_ok');

          // Auto-reattach if recovering from disconnect
          if (this.activeSessionId) {
            this.attachSession(this.activeSessionId, this.lastReceivedSeq);
          } else {
            this.getStats();
            this.listProfiles();
          }
          return;
        }

        if (msg.type === 'ERROR') {
          console.error('RemoteViber Error:', msg.error);
          this.emit('_handshake_err', msg.error);
          this.emit('error', msg.error);
          if (msg.error && (msg.error.includes('token') || msg.error.includes('Authentication'))) {
            this.shouldReconnect = false;
            this._setStatus('error');
          }
          if (this.ws) {
            this.ws.close();
          }
          return;
        }

        // Encrypted frames (E2EE active)
        if (msg.iv && msg.data) {
          if (this.crypto.supported && this.crypto.aesKey) {
            const decrypted = await this.crypto.decryptJson(msg.iv, msg.data);
            if (decrypted) {
              this._handleDecryptedMessage(decrypted);
            }
          }
        } else if (msg.type && msg.type !== 'WELCOME' && msg.type !== 'ERROR') {
          // Direct JSON frames over trusted Tailscale/Localhost channel
          this._handleDecryptedMessage(msg);
        }
      } catch (err) {
        console.error('Failed to process message:', err);
      }
    };

    this.ws.onclose = () => {
      this._stopHeartbeat();
      if (this.status === 'handshake') {
        this._setStatus('error');
      } else if (this.shouldReconnect) {
        this._setStatus('reconnecting');
        this._scheduleReconnect();
      } else {
        this._setStatus('disconnected');
      }
    };
  }

  _handleDecryptedMessage(msg) {
    const type = msg.type;

    if (type === 'PONG') {
      const now = Date.now();
      if (msg.ts) {
        this.pingMs = Math.max(1, now - msg.ts);
        this.emit('ping', this.pingMs);
      }
    } else if (type === 'STATS') {
      this.emit('stats', msg);
    } else if (type === 'PROFILES') {
      this.emit('profiles', msg.profiles);
    } else if (type === 'SESSION_ATTACHED') {
      this.activeSessionId = msg.session.session_id;
      this.lastReceivedSeq = msg.current_seq;
      this.emit('session_attached', msg);
    } else if (type === 'TERMINAL_OUTPUT') {
      if (msg.seq) {
        this.lastReceivedSeq = Math.max(this.lastReceivedSeq, msg.seq);
      }
      this.emit('terminal_output', msg);
    } else if (type === 'AGENT_LAUNCHED') {
      this.emit('agent_launched', msg.session);
    } else if (type === 'AGENT_TERMINATED') {
      this.emit('agent_terminated', msg);
    } else if (type === 'PROFILE_SAVED') {
      this.emit('profile_saved', msg.profile);
    } else if (type === 'DIR_LIST') {
      this.emit('dir_list', msg);
    } else if (type === 'DIR_CREATED') {
      this.emit('dir_created', msg);
    } else if (type === 'SESSION_FOLDER_UPDATED') {
      this.emit('session_folder_updated', msg);
    } else if (type === 'SESSION_DELETED') {
      this.emit('session_deleted', msg);
    } else if (type === 'SESSION_RESTARTED') {
      this.emit('session_restarted', msg.session);
    } else if (type === 'AGENT_ERROR') {
      this.emit('agent_error', msg.error);
    }
  }

  async send(msg) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return false;
    }
    if (this.crypto.supported && this.crypto.aesKey) {
      const encrypted = await this.crypto.encryptJson(msg);
      this.ws.send(JSON.stringify(encrypted));
    } else {
      this.ws.send(JSON.stringify(msg));
    }
    return true;
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    this.pingTimer = setInterval(() => {
      if (this.status === 'connected') {
        this.send({ type: 'PING', ts: Date.now() });
      }
    }, 4000);
  }

  _stopHeartbeat() {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  _scheduleReconnect() {
    if (this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.shouldReconnect && this.status !== 'connected') {
        this.connect();
      }
    }, 3000);
  }

  _setStatus(newStatus) {
    this.status = newStatus;
    this.emit('status_change', { status: newStatus, mode: this.connectionMode });
  }

  disconnect() {
    this.shouldReconnect = false;
    this._stopHeartbeat();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this._setStatus('disconnected');
  }

  // High-level Actions
  getStats() {
    return this.send({ type: 'GET_STATS' });
  }

  listProfiles() {
    return this.send({ type: 'LIST_PROFILES' });
  }

  saveProfile(profile) {
    return this.send({ type: 'SAVE_PROFILE', profile });
  }

  deleteProfile(profileId) {
    return this.send({ type: 'DELETE_PROFILE', profile_id: profileId });
  }

  launchAgent(options) {
    return this.send({
      type: 'LAUNCH_AGENT',
      ...options,
    });
  }

  attachSession(sessionId, lastSeq = 0) {
    this.activeSessionId = sessionId;
    return this.send({
      type: 'ATTACH_SESSION',
      session_id: sessionId,
      last_seq: lastSeq,
    });
  }

  detachSession() {
    this.activeSessionId = null;
    return this.send({ type: 'DETACH_SESSION' });
  }

  sendInput(sessionId, base64Data) {
    return this.send({
      type: 'TERMINAL_INPUT',
      session_id: sessionId,
      data: base64Data,
    });
  }

  resizeTerminal(sessionId, rows, cols) {
    return this.send({
      type: 'RESIZE_TERMINAL',
      session_id: sessionId,
      rows,
      cols,
    });
  }

  terminateAgent(sessionId) {
    return this.send({
      type: 'TERMINATE_AGENT',
      session_id: sessionId,
    });
  }

  listDirectory(path = '', reqId = '') {
    return this.send({
      type: 'LIST_DIR',
      path: path,
      req_id: reqId,
    });
  }

  createDirectory(path) {
    return this.send({
      type: 'CREATE_DIR',
      path: path,
    });
  }

  launchTerminal(options = {}) {
    return this.send({
      type: 'LAUNCH_TERMINAL',
      ...options,
    });
  }

  updateSessionFolder(sessionId, folder) {
    return this.send({
      type: 'UPDATE_SESSION_FOLDER',
      session_id: sessionId,
      folder: folder,
    });
  }

  deleteSession(sessionId) {
    return this.send({
      type: 'DELETE_SESSION',
      session_id: sessionId,
    });
  }

  restartSession(sessionId) {
    return this.send({
      type: 'RESTART_SESSION',
      session_id: sessionId,
    });
  }
}
