// Decodes the obfuscator.io CH array served by the studio's data.js.
// Usage: node keys/decode-data.js /tmp/data.js > keys/keys.json
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');
const ctx = {};
vm.createContext(ctx);
vm.runInContext(src + '\n;globalThis.__OUT = CH;', ctx, { timeout: 5000 });

const ch = ctx.__OUT;
if (!Array.isArray(ch) || ch.length === 0) {
  throw new Error('CH array missing from data.js');
}
const streams = ch.map((row) => {
  const [name, url, pair] = row;
  const [kid, key] = String(pair).split(':');
  if (!/^[0-9a-f]{32}$/i.test(kid || '') || !/^[0-9a-f]{32}$/i.test(key || '')) {
    throw new Error('bad KID:KEY pair for ' + name);
  }
  return { name, url, kid, key };
});
process.stdout.write(JSON.stringify({ streams }, null, 2) + '\n');
