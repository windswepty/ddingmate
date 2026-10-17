// 디스코드·KV 를 흉내 내고 Worker 를 그대로 돌려 본다: node --test test/
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import worker, { validateInquiry, realAccount, sha256, verifyDiscord, LIMITS } from '../src/index.js';

const DEV = '726781498606354452';
const CHANNEL = '111111111111111111';

class FakeKV {
  constructor() { this.map = new Map(); this.writes = 0; }
  async get(k) { return this.map.has(k) ? this.map.get(k) : null; }
  async put(k, v) { this.writes++; this.map.set(k, String(v)); }
}

let env, sent, waits, keys, nextMessage;

async function makeKeys() {
  const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
  return { pair, publicHex: Buffer.from(raw).toString('hex') };
}

beforeEach(async () => {
  keys = keys || await makeKeys();
  env = { DB: new FakeKV(), CHANNEL_ID: CHANNEL, DEV_USER_ID: DEV, DISCORD_TOKEN: 'bot-token', DISCORD_PUBLIC_KEY: keys.publicHex };
  sent = [];
  waits = [];
  nextMessage = 1000;
  globalThis.fetch = async (url, init) => {
    let body = init.body;
    let files = [];
    if (body instanceof FormData) {
      files = [...body.entries()].filter(([k]) => k.startsWith('files[')).map(([, v]) => v.name);
      body = JSON.parse(body.get('payload_json'));
    } else body = JSON.parse(body);
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
  assert.ok(env.DB.map.has(`t:${hash}`));
  assert.ok(![...env.DB.map.values()].some((v) => v.includes(TOKEN)), '번호표 원문이 저장됨');
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
  env.DB.map.set(`ad:${day}`, String(LIMITS.globalDaily));
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
  assert.equal(await verifyDiscord('zz', 'zz', '1', 'x'), false);
});
