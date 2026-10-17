// 실제 Workers 실행기(workerd, wrangler dev)로 띄워 서명 확인·KV·FormData 가 Workers 에서도 되는지 본다.
// 디스코드에는 가짜 토큰이라 올리지 못한다(문의 올리기는 502 가 맞다). 실행: node test/smoke.mjs
import { spawn } from 'node:child_process';
import { writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const PORT = 8799;
const BASE = `http://127.0.0.1:${PORT}`;
const DEV = '726781498606354452';

const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
const pub = Buffer.from(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey))).toString('hex');
writeFileSync(join(here, 'wrangler.smoke.toml'), `name = "ddingmate-relay-smoke"
main = "../src/index.js"
compatibility_date = "2026-09-01"
[vars]
CHANNEL_ID = "111111111111111111"
DEV_USER_ID = "${DEV}"
DISCORD_TOKEN = "fake"
DISCORD_PUBLIC_KEY = "${pub}"
[[kv_namespaces]]
binding = "DB"
id = "smoke-local"
`);

// 경로에 띄어쓰기·한글이 있어 셸 없이 wrangler 를 바로 띄운다
const wranglerBin = join(here, '..', 'node_modules', 'wrangler', 'bin', 'wrangler.js');
const child = spawn(process.execPath, [wranglerBin, 'dev', '--config', join(here, 'wrangler.smoke.toml'), '--port', String(PORT), '--ip', '127.0.0.1',
  '--persist-to', join(here, '.smoke-state')], { cwd: join(here, '..'), env: { ...process.env, WRANGLER_SEND_METRICS: 'false' } });
let log = '';
child.stdout.on('data', (d) => (log += d));
child.stderr.on('data', (d) => (log += d));

let failures = 0;
function check(ok, what) {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`);
  if (!ok) failures++;
}

async function waitReady() {
  for (let i = 0; i < 120; i++) {
    try {
      const r = await fetch(BASE + '/');
      if (r.ok) return true;
    } catch (e) { /* 아직 안 뜸 */ }
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

async function signed(body) {
  const ts = String(Math.floor(Date.now() / 1000));
  const raw = JSON.stringify(body);
  const sig = Buffer.from(new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, pair.privateKey, new TextEncoder().encode(ts + raw)))).toString('hex');
  return fetch(BASE + '/interactions', { method: 'POST', body: raw, headers: { 'content-type': 'application/json', 'x-signature-ed25519': sig, 'x-signature-timestamp': ts } });
}

try {
  if (!(await waitReady())) throw new Error('wrangler dev 가 안 뜸: ' + log.slice(-3000));
  check(true, 'wrangler dev 가 뜸');
  check((await (await fetch(BASE + '/')).text()) === 'ddingmate relay ok', 'GET /');
  const bad = await fetch(BASE + '/interactions', { method: 'POST', body: '{"type":1}', headers: { 'x-signature-ed25519': '00'.repeat(64), 'x-signature-timestamp': '1' } });
  check(bad.status === 401, `틀린 서명 거절 (${bad.status})`);
  const ping = await signed({ type: 1 });
  check(ping.status === 200 && (await ping.json()).type === 1, 'PING → PONG (Workers 의 Ed25519)');
  const stranger = await (await signed({ type: 3, member: { user: { id: '1' } }, data: { custom_id: 'reply:abcd1234' } })).json();
  check(stranger.type === 4 && stranger.data.flags === 64, '개발자 아닌 사람 단추 → 숨은 안내');
  const missing = await (await signed({ type: 3, member: { user: { id: DEV } }, data: { custom_id: 'reply:abcd1234' } })).json();
  check(missing.type === 4 && missing.data.content.includes('찾을 수 없어요'), '없는 문의 단추 → 안내');
  const rep = await fetch(BASE + '/replies', { method: 'POST', body: JSON.stringify({ tokens: ['A'.repeat(43)] }), headers: { 'content-type': 'application/json' } });
  const repBody = await rep.json();
  check(rep.status === 200 && repBody.tickets[0].found === false, '/replies (KV 읽기)');
  const form = new FormData();
  form.append('payload_json', JSON.stringify({ category: 'BUG', text: '스모크 시험 문의예요', token: 'C'.repeat(43),
    info: { name: 'Player0', uuid: '?', modVersion: '0.3.2', gameVersion: '1.21.4', server: '혼자 하기', place: '모름' } }));
  form.append('files[0]', new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'a.png', { type: 'image/png' }));
  const inq = await fetch(BASE + '/inquiry', { method: 'POST', body: form });
  check(inq.status === 502, `문의 올리기: FormData 를 읽고 디스코드까지 감(가짜 토큰이라 502) (${inq.status})`);
  const badInq = await fetch(BASE + '/inquiry', { method: 'POST', body: JSON.stringify({ category: 'BUG', text: 'x' }), headers: { 'content-type': 'application/json' } });
  check(badInq.status === 400, '잘못된 문의 400');
} finally {
  child.kill();
  if (process.platform === 'win32') spawn('taskkill', ['/pid', String(child.pid), '/t', '/f']);
  rmSync(join(here, 'wrangler.smoke.toml'), { force: true });
}
if (failures) {
  console.log(log.slice(-3000));
  process.exit(1);
}
console.log('스모크 시험 통과');
