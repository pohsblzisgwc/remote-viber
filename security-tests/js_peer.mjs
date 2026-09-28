import readline from 'node:readline';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
const root = process.env.VIBER_SOURCE_ROOT || path.resolve(import.meta.dirname, '../payload');
const { ClientCryptoManager, fingerprint } = await import(pathToFileURL(path.join(root, 'viber-client/src/crypto/e2ee.js')));
let client = new ClientCryptoManager();
for await (const line of readline.createInterface({ input: process.stdin })) {
  try {
    const msg = JSON.parse(line);
    let result;
    if (msg.op === 'hello') result = await client.initialize(msg.config);
    else if (msg.op === 'challenge') result = await client.establishSession(msg.data);
    else if (msg.op === 'decrypt') result = await client.decryptJson(msg.data);
    else if (msg.op === 'encrypt') result = await client.encryptJson(msg.data);
    else if (msg.op === 'fingerprint') result = await fingerprint(msg.data);
    else if (msg.op === 'phase') result = client.phase;
    else throw new Error('Unknown operation');
    console.log(JSON.stringify({ ok: true, result }));
  } catch (error) { console.log(JSON.stringify({ ok: false, error: error.message })); }
}
