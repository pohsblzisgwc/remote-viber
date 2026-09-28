/** Per-socket protocol state; async receive/send operations are serialized. */
import { ClientCryptoManager } from '../crypto/e2ee.js';

export class ViberConnection {
  constructor(config = {}) {
    this.config = config;
    this.ws = null;
    this.crypto = null;
    this.status = 'disconnected';
    this.connectionMode = 'unknown';
    this.pingMs = 0;
    this.pingTimer = null;
    this.listeners = new Map();
    this.reconnectTimer = null;
    this.shouldReconnect = true;
    this.activeSessionId = null;
    this.lastReceivedSeq = 0;
    this._generation = 0;
    this._connecting = false;
    this._ctx = null;
  }
  on(event, callback) {
    if (!this.listeners.has(event)) this.listeners.set(event, new Set());
    this.listeners.get(event).add(callback);
    return () => this.listeners.get(event)?.delete(callback);
  }
  emit(event, ...args) {
    this.listeners.get(event)?.forEach((fn) => {
      try { fn(...args); } catch (error) { console.error('Event handler failed', error); }
    });
  }
  async connect() {
    if (this._connecting || this.status === 'connected') return;
    this.shouldReconnect = true;
    if (!this.config.hostPub || !this.config.token) {
      this.shouldReconnect = false;
      this._setStatus('error');
      this.emit('error', 'Authentication: import a protocol-v2 pairing bundle from the host');
      return;
    }
    this._connecting = true;
    const generation = ++this._generation;
    this._setStatus('connecting');
    try {
      const candidates = this._buildCandidateUrls();
      for (const candidate of candidates) {
        if (generation !== this._generation || !this.shouldReconnect) return;
        try {
          await this._attemptSocket(candidate.url, candidate.mode, generation);
          return;
        } catch (error) {
          if (error.security) {
            this.shouldReconnect = false;
            this._setStatus('error');
            this.emit('error', `Authentication: ${error.message}`);
            return;
          }
        }
      }
      if (generation === this._generation) {
        this._setStatus('disconnected');
        this._scheduleReconnect();
      }
    } catch (error) {
      if (generation === this._generation) {
        this.shouldReconnect = false;
        this._setStatus('error');
        this.emit('error', error.message);
      }
    } finally {
      if (generation === this._generation) this._connecting = false;
    }
  }
  _buildCandidateUrls() {
    const port = this.config.directPort || 8765;
    const result = [], seen = new Set();
    const add = (value, mode) => {
      if (!value) return;
      const u = new URL(value);
      if (!['ws:', 'wss:'].includes(u.protocol) || u.username || u.password || u.hash) throw new Error('Invalid endpoint URL');
      if (mode === 'relay' && u.protocol !== 'wss:' && !['127.0.0.1', 'localhost', '[::1]'].includes(u.hostname)) {
        throw new Error('Remote relay requires wss://');
      }
      if (!seen.has(u.href)) { seen.add(u.href); result.push({ url: u.href, mode }); }
    };
    if (this.config.directUrl) add(this.config.directUrl, 'direct');
    if (typeof window !== 'undefined' && ['http:', 'https:'].includes(window.location.protocol)) {
      add(`${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`, 'direct');
    }
    const addHost = (host, mode) => {
      if (typeof host !== 'string' || !host || /[\s/?#@%]/.test(host)) throw new Error('Invalid host address');
      const formatted = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
      add(`ws://${formatted}:${port}/ws`, mode);
    };
    for (const ip of this.config.tailscaleIps || []) addHost(ip, 'tailscale');
    for (const ip of this.config.lanIps || []) addHost(ip, ['localhost', '127.0.0.1', '::1'].includes(ip) ? 'localhost' : 'lan');
    if (this.config.relayUrl && this.config.hostId) {
      const base = this.config.relayUrl.replace(/^http/, 'ws').replace(/\/$/, '');
      add(`${base}/connect/client?host_id=${encodeURIComponent(this.config.hostId)}`, 'relay');
    }
    return result;
  }
  _attemptSocket(url, mode, generation) {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(url);
      const crypto = new ClientCryptoManager();
      const ctx = { socket, crypto, txTail: Promise.resolve(), pending: 0 };
      this.ws = socket; this.crypto = crypto; this._ctx = ctx;
      let settled = false, connected = false, failed = false;
      let rxTail = Promise.resolve(), pendingReceives = 0;
      const current = () => generation === this._generation && this._ctx === ctx;
      const fail = (error, security = false) => {
        if (failed) return;
        failed = true;
        clearTimeout(timer);
        const wasCurrent = current();
        crypto.destroy();
        try { socket.close(security ? 1008 : 1000, 'Session closed'); } catch (_) { /* already closed */ }
        if (wasCurrent) { this.ws = null; this._ctx = null; }
        error.security = security;
        if (!settled) { settled = true; reject(error); }
        if (connected && wasCurrent) {
          this._stopHeartbeat();
          if (security) {
            this.shouldReconnect = false;
            this._setStatus('error');
            this.emit('error', 'Authentication: invalid encrypted session');
          } else if (this.shouldReconnect) {
            this._setStatus('reconnecting'); this._scheduleReconnect();
          } else this._setStatus('disconnected');
        }
      };
      ctx.fail = fail;
      const timer = setTimeout(() => fail(new Error('Handshake timed out')), 10000);
      socket.onopen = async () => {
        if (!current()) return;
        this.connectionMode = mode;
        this._setStatus('handshake');
        try {
          const hello = await crypto.initialize(this.config);
          if (current() && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(hello));
        } catch (error) { if (current()) fail(error, true); }
      };
      socket.onmessage = (event) => {
        if (!current() || failed) return;
        if (++pendingReceives > 64) { fail(new Error('Receive queue limit exceeded'), true); return; }
        rxTail = rxTail.then(async () => {
          if (!current() || failed) return;
          if (typeof event.data !== 'string' || event.data.length > 1024 * 1024) throw new Error('Invalid frame');
          const frame = JSON.parse(event.data);
          if (crypto.phase === 'hello') {
            const auth = await crypto.establishSession(frame);
            if (current() && !failed) socket.send(JSON.stringify(auth));
            return;
          }
          const msg = await crypto.decryptJson(frame);
          if (!current() || failed) return;
          if (!connected) {
            // decryptJson accepts only an authenticated WELCOME at this phase.
            connected = true; settled = true; clearTimeout(timer);
            this._setStatus('connected');
            this.emit('ready', { hostPub: this.config.hostPub, fingerprint: msg.fingerprint, e2ee: true });
            this._startHeartbeat(); resolve();
            if (this.activeSessionId) this.attachSession(this.activeSessionId, this.lastReceivedSeq);
            else { this.getStats(); this.listProfiles(); }
          } else this._handleDecryptedMessage(msg);
        }).catch((error) => { if (current()) fail(error, true); }).finally(() => { pendingReceives--; });
      };
      socket.onerror = () => fail(new Error('WebSocket connection failed'));
      socket.onclose = () => fail(new Error('WebSocket disconnected'));
    });
  }
  _handleDecryptedMessage(msg) {
    const type = msg.type;
    if (type === 'PONG' && msg.ts) {
      this.pingMs = Math.max(1, Date.now() - msg.ts); this.emit('ping', this.pingMs);
    } else if (type === 'SESSION_ATTACHED') {
      this.activeSessionId = msg.session.session_id; this.lastReceivedSeq = msg.current_seq;
      this.emit('session_attached', msg);
    } else if (type === 'TERMINAL_OUTPUT') {
      if (msg.session_id === this.activeSessionId && msg.seq) this.lastReceivedSeq = Math.max(this.lastReceivedSeq, msg.seq);
      this.emit('terminal_output', msg);
    } else {
      const mapping = {
        STATS: ['stats', msg], PROFILES: ['profiles', msg.profiles],
        AGENT_LAUNCHED: ['agent_launched', msg.session], AGENT_TERMINATED: ['agent_terminated', msg],
        PROFILE_SAVED: ['profile_saved', msg.profile], DIR_LIST: ['dir_list', msg], DIR_CREATED: ['dir_created', msg],
        SESSION_FOLDER_UPDATED: ['session_folder_updated', msg], SESSION_DELETED: ['session_deleted', msg],
        SESSION_RESTARTED: ['session_restarted', msg.session], AGENT_ERROR: ['agent_error', msg.error],
      };
      if (mapping[type]) this.emit(...mapping[type]);
    }
  }
  send(message) {
    const ctx = this._ctx;
    if (!ctx || this.status !== 'connected' || ctx.socket.readyState !== WebSocket.OPEN || ctx.pending >= 64) return Promise.resolve(false);
    ctx.pending++;
    const work = ctx.txTail.then(async () => {
      if (this._ctx !== ctx || ctx.socket.readyState !== WebSocket.OPEN) return false;
      if (ctx.socket.bufferedAmount > 1024 * 1024) throw new Error('Slow connection');
      const frame = await ctx.crypto.encryptJson(message);
      if (this._ctx !== ctx || ctx.socket.readyState !== WebSocket.OPEN) return false;
      ctx.socket.send(JSON.stringify(frame));
      return true;
    }).catch((error) => {
      if (this._ctx === ctx) ctx.fail(error, true);
      return false;
    }).finally(() => { ctx.pending--; });
    ctx.txTail = work;
    return work;
  }
  _startHeartbeat() {
    this._stopHeartbeat();
    this.pingTimer = setInterval(() => { if (this.status === 'connected') this.send({ type: 'PING', ts: Date.now() }); }, 4000);
  }
  _stopHeartbeat() { if (this.pingTimer) clearInterval(this.pingTimer); this.pingTimer = null; }
  _scheduleReconnect() {
    if (!this.shouldReconnect || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.shouldReconnect && this.status !== 'connected') this.connect();
    }, 3000);
  }
  _setStatus(status) { this.status = status; this.emit('status_change', { status, mode: this.connectionMode }); }
  disconnect() {
    this.shouldReconnect = false;
    ++this._generation;
    this._connecting = false;
    this._stopHeartbeat();
    clearTimeout(this.reconnectTimer); this.reconnectTimer = null;
    const ctx = this._ctx;
    this._ctx = null; this.ws = null;
    if (ctx) ctx.fail(new Error('User disconnected'));
    this._setStatus('disconnected');
  }
  getStats() { return this.send({ type: 'GET_STATS' }); }
  listProfiles() { return this.send({ type: 'LIST_PROFILES' }); }
  saveProfile(profile) { return this.send({ type: 'SAVE_PROFILE', profile }); }
  deleteProfile(profileId) { return this.send({ type: 'DELETE_PROFILE', profile_id: profileId }); }
  launchAgent(options) { return this.send({ ...options, type: 'LAUNCH_AGENT' }); }
  attachSession(sessionId, lastSeq = 0, fullHistory = false) {
    this.activeSessionId = sessionId;
    return this.send({ type: 'ATTACH_SESSION', session_id: sessionId, last_seq: lastSeq, full_history: Boolean(fullHistory) });
  }
  detachSession() { this.activeSessionId = null; return this.send({ type: 'DETACH_SESSION' }); }
  sendInput(sessionId, base64Data) { return this.send({ type: 'TERMINAL_INPUT', session_id: sessionId, data: base64Data }); }
  resizeTerminal(sessionId, rows, cols) { return this.send({ type: 'RESIZE_TERMINAL', session_id: sessionId, rows, cols }); }
  terminateAgent(sessionId) { return this.send({ type: 'TERMINATE_AGENT', session_id: sessionId }); }
  listDirectory(path = '', reqId = '') { return this.send({ type: 'LIST_DIR', path, req_id: reqId }); }
  createDirectory(path) { return this.send({ type: 'CREATE_DIR', path }); }
  launchTerminal(options = {}) { return this.send({ ...options, type: 'LAUNCH_TERMINAL' }); }
  updateSessionFolder(sessionId, folder) { return this.send({ type: 'UPDATE_SESSION_FOLDER', session_id: sessionId, folder }); }
  deleteSession(sessionId) { return this.send({ type: 'DELETE_SESSION', session_id: sessionId }); }
  restartSession(sessionId) { return this.send({ type: 'RESTART_SESSION', session_id: sessionId }); }
}
