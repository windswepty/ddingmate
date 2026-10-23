// 디스코드·KV 를 흉내 내고 Worker 를 그대로 돌려 본다: node --test test/
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker, { validateInquiry, realAccount, sha256, verifyDiscord, cleanup, LIMITS, NOTICE_COMMAND } from '../src/index.js';

const DEV = '726781498606354452';
const CHANNEL = '111111111111111111';

// D1 흉내: 진짜 SQLite(node:sqlite)에 migrations/ 를 적용하고 D1 과 같은 모양(prepare·bind·first·all·run·batch)으로
class FakeD1 {
  constructor() {
    this.db = new DatabaseSync(':memory:');
    for (const m of ['0001_init.sql', '0002_notices.sql']) this.db.exec(readFileSync(new URL('../migrations/' + m, import.meta.url), 'utf-8'));
    this.writes = 0;
  }
  prepare(sql) {
    const d1 = this;
    const make = (args) => ({
      bind: (...a) => make(a),
      run() {
        if (!/^\s*select/i.test(sql)) d1.writes++;
        return /returning/i.test(sql) ? d1.db.prepare(sql).all(...args) : d1.db.prepare(sql).run(...args);
      },
      async first(col) {
        const row = /^\s*select/i.test(sql) ? d1.db.prepare(sql).get(...args) : this.run()[0];
        if (!row) return null;
        return col ? (row[col] ?? null) : { ...row };
      },
      async all() {
        const rows = /^\s*select/i.test(sql) ? d1.db.prepare(sql).all(...args) : this.run();
        return { results: Array.isArray(rows) ? rows.map((r) => ({ ...r })) : [] };
      },
    });
    return make([]);
  }
  async batch(stmts) {
    this.db.exec('BEGIN');
    try {
      const out = [];
      for (const st of stmts) out.push(await st.all());
      this.db.exec('COMMIT');
      return out;
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }
  rows(sql, ...a) { return this.db.prepare(sql).all(...a).map((r) => ({ ...r })); }
}

let env, sent, waits, keys, nextMessage;

async function makeKeys() {
  const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
  return { pair, publicHex: Buffer.from(raw).toString('hex') };
}

beforeEach(async () => {
  keys = keys || await makeKeys();
  env = { DB: new FakeD1(), CHANNEL_ID: CHANNEL, DEV_USER_ID: DEV, DISCORD_TOKEN: 'bot-token', DISCORD_PUBLIC_KEY: keys.publicHex };
  sent = [];
  waits = [];
  nextMessage = 1000;
  globalThis.fetch = async (url, init) => {
    let body = init.body;
    let files = [];
    if (body instanceof FormData) {
      files = [...body.entries()].filter(([k]) => k.startsWith('files[')).map(([, v]) => v.name);
      body = JSON.parse(body.get('payload_json'));
    } else body = body === undefined ? null : JSON.parse(body);
    sent.push({ url, method: init.method, auth: init.headers.authorization, body, files });
    return new Response(JSON.stringify({ id: String(nextMessage++) }), { status: 200 });
  };
});

const ctx = () => ({ waitUntil: (p) => waits.push(p) });
const settle = () => Promise.all(waits.splice(0));
const TOKEN = 'A'.repeat(43);
const TOKEN2 = 'B'.repeat(43);

function inquiryBody(over = {}) {
  return {
    category: 'BUG',
    text: '구리 블록 그림이 청록색이에요 @everyone',
    token: TOKEN,
    info: { name: 'windswepty', uuid: '7b27dbf2-112b-48cb-afb1-92a7ffcb4837', modVersion: '0.3.2', gameVersion: '1.21.4', server: 'ddingtycoon.kr', place: '스폰 1채널' },
    ...over,
  };
}

async function post(path, body, headers = {}, ip = '1.2.3.4') {
  const init = { method: 'POST', headers: { 'cf-connecting-ip': ip, ...headers } };
  if (body instanceof FormData) init.body = body;
  else {
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
    init.headers['content-type'] = 'application/json';
  }
  const res = await worker.fetch(new Request('https://relay.test' + path, init), env, ctx());
  return { status: res.status, body: await res.json().catch(() => null) };
}

async function signed(body) {
  const ts = String(Math.floor(Date.now() / 1000));
  const raw = JSON.stringify(body);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, keys.pair.privateKey, new TextEncoder().encode(ts + raw)));
  return post('/interactions', raw, { 'x-signature-ed25519': Buffer.from(sig).toString('hex'), 'x-signature-timestamp': ts });
}

test('문의: 디스코드에 임베드와 [답변 보내기] 단추로 올리고, 번호표는 해시로만 저장', async () => {
  const r = await post('/inquiry', inquiryBody());
  assert.equal(r.status, 200);
  assert.match(r.body.id, /^[0-9a-f]{8}$/);
  assert.equal(sent.length, 1);
  const m = sent[0];
  assert.equal(m.url, `https://discord.com/api/v10/channels/${CHANNEL}/messages`);
  assert.equal(m.auth, 'Bot bot-token');
  assert.equal(m.body.content, `<@${DEV}> 새 문의가 들어왔어요 · 버그 제보`);
  assert.deepEqual(m.body.allowed_mentions, { parse: [], users: [DEV] });
  assert.equal(m.body.embeds[0].title, '[버그 제보] windswepty');
  assert.match(m.body.embeds[0].fields[0].value, /namemc\.com\/profile/);
  assert.equal(m.body.embeds[0].footer.text, `문의 번호 ${r.body.id}`);
  assert.equal(m.body.components[0].components[0].custom_id, `reply:${r.body.id}`);
  const hash = await sha256(TOKEN);
  const rows = env.DB.rows('SELECT * FROM tickets');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].token_hash, hash);
  assert.equal(rows[0].message_id, '1000');
  assert.ok(!JSON.stringify(env.DB.rows('SELECT * FROM tickets')).includes(TOKEN), '번호표 원문이 저장됨');
});

test('문의: 한글 닉네임이 있으면 "windswepty(멜트)"로 올리고 답변 창에도', async () => {
  const r = await post('/inquiry', inquiryBody({ info: { ...inquiryBody().info, nick: '멜트' } }));
  assert.equal(r.status, 200);
  assert.equal(sent[0].body.embeds[0].title, '[버그 제보] windswepty(멜트)');
  assert.match(sent[0].body.embeds[0].fields[0].value, /^windswepty\(멜트\)\n/);
  const modal = await signed({ type: 3, member: { user: { id: DEV } }, data: { custom_id: `reply:${r.body.id}` } });
  assert.equal(modal.body.data.title, 'windswepty(멜트) 님에게 답변');
});

test('문의: 이미지 첨부는 image1.png 로 바꿔 올리고 첫 장을 임베드 그림으로', async () => {
  const form = new FormData();
  form.append('payload_json', JSON.stringify(inquiryBody()));
  form.append('files[0]', new File([new Uint8Array([0x89, 0x50])], '내 스크린샷.png', { type: 'image/png' }));
  form.append('files[1]', new File([new Uint8Array([1])], 'b.jpg', { type: 'image/jpeg' }));
  const r = await post('/inquiry', form);
  assert.equal(r.status, 200);
  assert.deepEqual(sent[0].files, ['image1.png', 'image2.jpg']);
  assert.equal(sent[0].body.embeds[0].image.url, 'attachment://image1.png');
});

test('문의: 잘못된 내용·도배는 막는다', async () => {
  assert.equal((await post('/inquiry', inquiryBody({ text: '짧음' }))).status, 400);
  assert.equal((await post('/inquiry', inquiryBody({ category: 'NOPE' }))).status, 400);
  assert.equal((await post('/inquiry', inquiryBody({ token: 'short' }))).status, 400);
  assert.equal((await post('/inquiry', inquiryBody({ info: { ...inquiryBody().info, name: '<@everyone>' } }))).status, 400);
  assert.equal((await post('/inquiry', 'not json')).status, 400);
  assert.equal(sent.length, 0);
  assert.equal((await post('/inquiry', inquiryBody())).status, 200);
  assert.equal((await post('/inquiry', inquiryBody({ token: TOKEN2 }))).status, 429, '60초 안에 또 보냄');
  assert.equal((await post('/inquiry', inquiryBody({ token: TOKEN2 }), {}, '5.6.7.8')).status, 200, '다른 주소는 됨');
  assert.equal((await post('/inquiry', inquiryBody(), {}, '9.9.9.9')).status, 400, '같은 번호표를 다시 씀');
  assert.equal(sent.length, 2);
});

test('문의: 하루 전체 한도', async () => {
  const day = new Date().toISOString().slice(0, 10);
  env.DB.db.prepare('INSERT INTO limits VALUES (?, ?, ?)').run(`day:${day}`, LIMITS.globalDaily, Date.now() + 3600_000);
  assert.equal((await post('/inquiry', inquiryBody())).status, 429);
  assert.equal(sent.length, 0);
});

test('디스코드 서명이 틀리면 거절, PING 에는 PONG', async () => {
  const bad = await post('/interactions', JSON.stringify({ type: 1 }), { 'x-signature-ed25519': '00'.repeat(64), 'x-signature-timestamp': '1' });
  assert.equal(bad.status, 401);
  const none = await post('/interactions', JSON.stringify({ type: 1 }));
  assert.equal(none.status, 401);
  const ping = await signed({ type: 1 });
  assert.deepEqual(ping.body, { type: 1 });
});

test('답변: 개발자만, 단추 → 입력창 → 저장 → 채널에 "아직 안 읽음" → 받아 가면 "읽음"', async () => {
  const id = (await post('/inquiry', inquiryBody())).body.id;
  const inquiryMsg = sent[0];
  sent.length = 0;

  const stranger = await signed({ type: 3, member: { user: { id: '999' } }, data: { custom_id: `reply:${id}` } });
  assert.equal(stranger.body.type, 4);
  assert.equal(stranger.body.data.flags, 64);

  const modal = await signed({ type: 3, member: { user: { id: DEV } }, data: { custom_id: `reply:${id}` } });
  assert.equal(modal.body.type, 9);
  assert.equal(modal.body.data.custom_id, `send:${id}`);
  assert.equal(modal.body.data.title, 'windswepty 님에게 답변');

  const before = await post('/replies', { tokens: [TOKEN] });
  assert.deepEqual(before.body.tickets[0].replies, []);

  const submit = await signed({ type: 5, user: { id: DEV }, data: { custom_id: `send:${id}`, components: [{ type: 1, components: [{ type: 4, custom_id: 'text', value: '고쳤어요!\n다음 버전에서 확인해 주세요' }] }] } });
  assert.equal(submit.body.type, 4);
  await settle();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].method, 'POST');
  assert.match(sent[0].body.content, /windswepty\*\* 님에게 답변 #1\n> 고쳤어요!\n> 다음 버전에서 확인해 주세요\n⏳ 아직 안 읽음/);
  assert.equal(sent[0].body.message_reference.message_id, '1000');
  assert.ok(inquiryMsg);

  const got = await post('/replies', { tokens: [TOKEN, TOKEN2] });
  assert.equal(got.status, 200);
  assert.equal(got.body.tickets[0].found, true);
  assert.equal(got.body.tickets[0].replies[0].text, '고쳤어요!\n다음 버전에서 확인해 주세요');
  assert.equal(got.body.tickets[0].replies[0].n, 1);
  assert.equal(got.body.tickets[1].found, false);
  assert.equal(got.body.pollSec, 120, '띵메이트에 확인 간격을 안 알려 줌');
  await settle();
  const patch = sent.find((s) => s.method === 'PATCH');
  assert.ok(patch, '읽음 표시를 안 함');
  assert.match(patch.url, /messages\/1001$/);
  assert.match(patch.body.content, /✅ 읽음/);

  // 다시 받아 가도 같은 답변이 오고(띵메이트가 본 번호로 거른다), 읽음 표시는 다시 안 고친다
  sent.length = 0;
  const writes = env.DB.writes;
  const again = await post('/replies', { tokens: [TOKEN] });
  await settle();
  assert.equal(again.body.tickets[0].replies.length, 1);
  assert.equal(sent.length, 0);
  assert.equal(env.DB.writes, writes, '이미 읽은 답변인데 또 저장함');
});

test('답변: 디스코드가 같은 제출을 두 번 보내도 한 번만, 연달아 보낸 답변은 #1 #2 로 다 남는다', async () => {
  const id = (await post('/inquiry', inquiryBody())).body.id;
  sent.length = 0;
  const submit = (iid, value) => signed({ id: iid, type: 5, user: { id: DEV }, data: { custom_id: `send:${id}`, components: [{ type: 1, components: [{ type: 4, custom_id: 'text', value }] }] } });
  const [a, b] = await Promise.all([submit('9001', '첫 답변'), submit('9001', '첫 답변')]);
  const c = await submit('9002', '두 번째 답변');
  await settle();
  const msgs = [a, b, c].map((r) => r.body.data.content);
  assert.equal(msgs.filter((m) => m.startsWith('답변을 저장했어요')).length, 2);
  assert.equal(msgs.filter((m) => m.startsWith('이미 저장한')).length, 1);
  assert.deepEqual(env.DB.rows('SELECT n, text FROM replies ORDER BY n'), [{ n: 1, text: '첫 답변' }, { n: 2, text: '두 번째 답변' }]);
  assert.equal(sent.filter((s) => s.method === 'POST').length, 2, '채널 기록이 답변 수와 다름');
  const got = await post('/replies', { tokens: [TOKEN] });
  assert.deepEqual(got.body.tickets[0].replies.map((r) => r.n), [1, 2]);
});

test('읽음이 채널 기록보다 먼저 와도 기록은 하나, "읽음"으로 고쳐진다', async () => {
  const id = (await post('/inquiry', inquiryBody())).body.id;
  sent.length = 0;
  await signed({ id: '7001', type: 5, user: { id: DEV }, data: { custom_id: `send:${id}`, components: [{ type: 1, components: [{ type: 4, custom_id: 'text', value: '빠른 답변' }] }] } });
  // 채널 기록(waitUntil)이 끝나기 전에 띵메이트가 받아 감
  const pending = waits.splice(0);
  await post('/replies', { tokens: [TOKEN] });
  await Promise.all(pending);
  await settle();
  const posts = sent.filter((s) => s.method === 'POST');
  const patches = sent.filter((s) => s.method === 'PATCH');
  assert.equal(posts.length, 1);
  assert.equal(patches.length, 1);
  assert.match(patches[0].body.content, /✅ 읽음/);
});

test('90일 지난 문의·답변과 끝난 도배 막기 기록은 지운다', async () => {
  await post('/inquiry', inquiryBody());
  const later = Date.now() + (LIMITS.ttlSec + 60) * 1000;
  await cleanup(env, later);
  assert.equal(env.DB.rows('SELECT * FROM tickets').length, 0);
  assert.equal(env.DB.rows('SELECT * FROM limits').length, 0);
});

test('답변 받기: 잘못된 요청은 거절', async () => {
  assert.equal((await post('/replies', { tokens: 'x' })).status, 400);
  assert.equal((await post('/replies', { tokens: ['bad'] })).status, 400);
  assert.equal((await post('/replies', { tokens: Array(LIMITS.maxTokensPerPoll + 1).fill(TOKEN) })).status, 400);
});

test('검사 함수', async () => {
  assert.equal(realAccount('7b27dbf2-112b-48cb-afb1-92a7ffcb4837'), true);
  assert.equal(realAccount('e4f1c3a2-1234-3abc-8def-0123456789ab'), false);
  assert.equal(validateInquiry(inquiryBody()), null);
  assert.equal(validateInquiry(inquiryBody({ text: 'x'.repeat(1501) })), 'bad text');
  const named = (name) => validateInquiry(inquiryBody({ info: { ...inquiryBody().info, name } }));
  assert.equal(named('.Bedrock Name'), null);
  assert.equal(named('Blo_9'), null);
  assert.equal(named('@everyone'), 'bad name');
  assert.equal(named('**굵게**'), 'bad name');
  const nicked = (nick) => validateInquiry(inquiryBody({ info: { ...inquiryBody().info, nick } }));
  assert.equal(nicked('멜트'), null);
  assert.equal(nicked('멜트_2'), null);
  assert.equal(nicked('<@everyone>'), 'bad nick');
  assert.equal(nicked('가'.repeat(17)), 'bad nick');
  assert.equal(nicked(3), 'bad nick');
  assert.equal(await verifyDiscord('zz', 'zz', '1', 'x'), false);
});

// ---------------------------------------------------------------- 개발자 공지

async function getNotices() {
  const res = await worker.fetch(new Request('https://relay.test/notices'), env, ctx());
  return { status: res.status, body: await res.json() };
}

function noticeSubmit(title, text, id = 'i1', user = DEV) {
  return signed({
    id, type: 5, member: { user: { id: user } },
    data: { custom_id: 'notice:new', components: [
      { type: 1, components: [{ custom_id: 'title', value: title }] },
      { type: 1, components: [{ custom_id: 'text', value: text }] },
    ] },
  });
}

test('공지: /공지 → 입력창 → 저장·채널 기록 → 띵메이트가 받아 감 → [공지 내리기]로 내리면 더는 안 감', async () => {
  const modal = await signed({ type: 2, member: { user: { id: DEV } }, data: { name: '공지' } });
  assert.equal(modal.body.type, 9);
  assert.equal(modal.body.data.custom_id, 'notice:new');
  assert.deepEqual(modal.body.data.components.map((r) => r.components[0].custom_id), ['title', 'text']);

  const r = await noticeSubmit('점검 안내', '오늘 밤 띵메이트 업데이트가 있어요\n@everyone');
  assert.equal(r.body.type, 4);
  assert.equal(r.body.data.flags, 64);
  await settle();
  assert.equal(sent.length, 1);
  assert.match(sent[0].body.content, /^📢 \*\*공지 · 점검 안내\*\*\n> 오늘 밤/);
  assert.deepEqual(sent[0].body.allowed_mentions, { parse: [] });
  const button = sent[0].body.components[0].components[0];
  assert.equal(button.label, '공지 내리기');
  const id = button.custom_id.slice('unnotice:'.length);
  assert.match(id, /^[0-9a-f]{8}$/);
  assert.equal(env.DB.rows('SELECT message_id FROM notices')[0].message_id, '1000');

  const got = await getNotices();
  assert.equal(got.status, 200);
  assert.equal(got.body.pollMin, 30);
  assert.equal(got.body.notices.length, 1);
  assert.deepEqual(Object.keys(got.body.notices[0]).sort(), ['at', 'id', 'text', 'title']);
  assert.equal(got.body.notices[0].title, '점검 안내');

  const down = await signed({ type: 3, member: { user: { id: DEV } }, data: { custom_id: `unnotice:${id}` } });
  assert.equal(down.body.type, 7);
  assert.deepEqual(down.body.data.components, []);
  assert.match(down.body.data.content, /내린 공지/);
  assert.equal((await getNotices()).body.notices.length, 0);
});

test('공지: 개발자만, 같은 제출은 한 번만, 새것부터 5개·30일까지', async () => {
  const other = await noticeSubmit('장난', '내용', 'x', '999');
  assert.match(other.body.data.content, /개발자만/);
  const cmd = await signed({ type: 2, member: { user: { id: '999' } }, data: { name: '공지' } });
  assert.match(cmd.body.data.content, /개발자만/);
  await noticeSubmit('첫 공지', '내용', 'same');
  const again = await noticeSubmit('첫 공지', '내용', 'same');
  assert.match(again.body.data.content, /이미 올린/);
  for (let i = 0; i < 6; i++) await noticeSubmit(`공지 ${i}`, '내용', `n${i}`);
  env.DB.db.prepare('UPDATE notices SET at = ? WHERE title = ?').run(Date.now() - 31 * 24 * 3600 * 1000, '공지 5');
  const got = (await getNotices()).body.notices;
  assert.equal(got.length, LIMITS.maxNotices);
  assert.ok(!got.some((n) => n.title === '공지 5'), '30일 지난 공지가 감');
  // 90일 지나면 정리
  env.DB.db.prepare('UPDATE notices SET at = 0').run();
  await cleanup(env, Date.now());
  assert.equal(env.DB.rows('SELECT * FROM notices').length, 0);
});

test('/공지 명령 등록: 열쇠가 맞을 때만, 앱 번호를 알아내 /공지 하나를 등록', async () => {
  env.ADMIN_KEY_HASH = await sha256('secret-key');
  const no = await post('/admin/commands', {});
  assert.equal(no.status, 403);
  const bad = await post('/admin/commands', {}, { 'x-admin-key': 'wrong' });
  assert.equal(bad.status, 403);
  const ok = await post('/admin/commands', {}, { 'x-admin-key': 'secret-key' });
  assert.equal(ok.status, 200);
  assert.equal(sent[0].method, 'GET');
  assert.equal(sent[0].url, 'https://discord.com/api/v10/oauth2/applications/@me');
  assert.equal(sent[1].method, 'PUT');
  assert.equal(sent[1].url, 'https://discord.com/api/v10/applications/1000/commands');
  assert.deepEqual(sent[1].body, [NOTICE_COMMAND]);
});
