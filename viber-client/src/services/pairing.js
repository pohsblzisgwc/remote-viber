import { fromBase64, fingerprint } from '../crypto/e2ee.js';

export async function parsePairingBundle(input) {
  if (typeof input !== 'string' || input.length > 16384) throw new Error('配对信息长度无效');
  let encoded = input.trim();
  if (encoded.startsWith('viber://')) {
    const url = new URL(encoded);
    if (url.hostname !== 'connect' || url.pathname || url.hash || url.searchParams.getAll('data').length !== 1 || [...url.searchParams.keys()].some((key) => key !== 'data') || url.username || url.password || url.port) {
      throw new Error('配对链接无效');
    }
    encoded = url.searchParams.get('data');
  }
  if (!/^[A-Za-z0-9_-]+={0,2}$/.test(encoded)) throw new Error('配对编码无效');
  const normal = encoded.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
  const padded = normal + '='.repeat((4 - normal.length % 4) % 4);
  const json = new TextDecoder('utf-8', { fatal: true }).decode(fromBase64(padded));
  const data = JSON.parse(json);
  if (!data || Array.isArray(data) || data.v !== 2 || typeof data.token !== 'string' || !/^[!-~]{32,256}$/.test(data.token || '')) {
    throw new Error('必须使用升级后的 v2 配对信息');
  }
  const digest = await fingerprint(data.pub);
  if (data.id !== `host-${digest}`) throw new Error('主机 ID 与身份公钥不匹配');
  if (!Number.isInteger(data.port) || data.port < 1 || data.port > 65535) throw new Error('端口无效');
  const addresses = (list) => {
    if (!Array.isArray(list) || list.length > 16 || list.some((x) => typeof x !== 'string' || !x || x.length > 253 || /[\s/?#@%]/.test(x))) {
      throw new Error('连接地址无效');
    }
    return [...list];
  };
  const endpoint = (value, relay = false) => {
    if (!value) return '';
    let normalized = value;
    if (typeof normalized === 'string') {
      if (normalized.startsWith('https://')) {
        normalized = 'wss://' + normalized.slice(8);
      } else if (normalized.startsWith('http://')) {
        normalized = 'ws://' + normalized.slice(7);
      }
    }
    const url = new URL(normalized);
    if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('端点 URL 无效');
    if (relay && url.protocol !== 'wss:' && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) throw new Error('远程中继必须使用 wss://');
    return url.href.replace(/\/$/, '');
  };
  return { protocolVersion: 2, hostId: data.id, hostPub: data.pub,
    hostName: typeof data.name === 'string' ? data.name.slice(0, 128) : '远程主机',
    directPort: data.port, token: data.token, tailscaleIps: addresses(data.tailscale || []),
    lanIps: addresses(data.lan || []), relayUrl: endpoint(data.relay, true),
    directUrl: endpoint(data.direct_url), ssl: Boolean(data.ssl), fingerprint: digest };
}

export function loadSessionConfig(key) {
  const empty = { protocolVersion: 2, hostId: '', hostPub: '', hostName: '请重新配对',
    token: '', directPort: 8765, tailscaleIps: [], lanIps: [], relayUrl: '', directUrl: '', ssl: false };
  try {
    // Purge v1 browser persistence. Never trust URL tokens or injected metadata.
    localStorage.removeItem('viber_host_config');
    const saved = JSON.parse(sessionStorage.getItem(key) || 'null');
    if (saved?.protocolVersion === 2 && saved.hostPub && saved.token) return saved;
  } catch (_) { /* unavailable storage -> explicit pairing */ }
  return empty;
}
