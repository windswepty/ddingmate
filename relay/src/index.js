// 띵메이트 문의 중계(Cloudflare Worker)
//
// 1) POST /inquiry      띵메이트 [문의 보내기] → 디스코드 채널에 임베드 + [답변 보내기] 단추로 올린다
// 2) POST /interactions 디스코드 단추·입력창(개발자만) → 답변을 저장하고 채널에 "답변 보냄"을 남긴다
// 3) POST /replies      띵메이트가 접속할 때·10분마다 자기 문의의 답변을 받아 간다(읽으면 채널에 "읽음" 표시)
//
// 문의마다 띵메이트가 만든 비밀 번호표(token)를 함께 받고, 저장은 그 해시로만 한다.
// 답변은 번호표를 가진 그 컴퓨터만 받을 수 있다(닉네임·UUID 를 알아도 남의 답변은 못 본다).
//
// 환경 값(wrangler.toml / 비밀 값):
//   DB                  KV 이름공간
//   CHANNEL_ID          문의가 올라갈 디스코드 채널 ID
//   DEV_USER_ID         멘션·답변할 개발자 디스코드 사용자 ID
//   DISCORD_TOKEN       (비밀) 봇 토큰
//   DISCORD_PUBLIC_KEY  (비밀 아님, 그래도 secret 로 넣어도 됨) 앱 공개 키(16진수)

const API = 'https://discord.com/api/v10';

export const CATEGORIES = {
  BUG: { label: '버그 제보', color: 0xe0524d },
  FEATURE: { label: '기능 추가 요청', color: 0x4fb54a },
  TEXT: { label: '표기·계산 오류', color: 0xf2b531 },
  QUESTION: { label: '질문', color: 0x3a6fd8 },
  OTHER: { label: '기타', color: 0x7c8390 },
};

export const LIMITS = {
  minText: 5,
  maxText: 1500,
  maxFiles: 3,
  maxFileBytes: 8 * 1024 * 1024,
  maxReply: 1000,
  // 같은 주소에서: 60초에 1번, 하루 20번. 전체: 하루 300번(채널 도배·무료 한도 지키기)
  perIpCooldownSec: 60,
  perIpDaily: 20,
  globalDaily: 300,
  maxTokensPerPoll: 20,
  // 문의 기록은 90일 뒤 저절로 지운다
  ttlSec: 90 * 24 * 3600,
};

const IMAGE_TYPES = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === 'POST' && url.pathname === '/inquiry') return await inquiry(request, env);
      if (request.method === 'POST' && url.pathname === '/interactions') return await interactions(request, env, ctx);
      if (request.method === 'POST' && url.pathname === '/replies') return await replies(request, env, ctx);
      if (request.method === 'GET' && url.pathname === '/') return text('ddingmate relay ok');
      return json({ error: 'not found' }, 404);
    } catch (e) {
      console.error('relay error', e && e.stack || e);
      return json({ error: 'server error' }, 500);
    }
  },
};

// ---------------------------------------------------------------- 문의 받기

async function inquiry(request, env) {
  const type = request.headers.get('content-type') || '';
  let payload;
  const files = [];
  if (type.startsWith('multipart/form-data')) {
    const form = await request.formData();
    payload = parseJson(form.get('payload_json'));
    for (const [key, value] of form.entries()) {
      if (!/^files\[\d+\]$/.test(key) || typeof value === 'string') continue;
      files.push(value);
    }
  } else {
    payload = parseJson(await request.text());
  }
  const bad = validateInquiry(payload, files);
  if (bad) return json({ error: bad }, 400);

  const ip = request.headers.get('cf-connecting-ip') || 'unknown';
  const day = new Date().toISOString().slice(0, 10);
  if (await env.DB.get(`rl:${ip}`)) return json({ error: 'cooldown' }, 429);
  const ipDay = Number(await env.DB.get(`rd:${day}:${ip}`)) || 0;
  const allDay = Number(await env.DB.get(`ad:${day}`)) || 0;
  if (ipDay >= LIMITS.perIpDaily || allDay >= LIMITS.globalDaily) return json({ error: 'daily limit' }, 429);

  const id = randomId();
  const tokenHash = await sha256(payload.token);
  if (await env.DB.get(`t:${tokenHash}`)) return json({ error: 'token reused' }, 400);

  const names = files.map((f, k) => `image${k + 1}.${ext(f.name)}`);
  const message = inquiryMessage(env, id, payload, names);
  const posted = await discord(env, 'POST', `/channels/${env.CHANNEL_ID}/messages`, message, files, names);
  if (!posted.ok) {
    console.error('discord post failed', posted.status, posted.body);
    return json({ error: 'discord' }, 502);
  }

  const record = {
    id,
    name: payload.info.name,
    category: payload.category,
    created: Date.now(),
    messageId: posted.body.id,
    replies: [],
  };
  await Promise.all([
    env.DB.put(`t:${tokenHash}`, JSON.stringify(record), { expirationTtl: LIMITS.ttlSec }),
    env.DB.put(`id:${id}`, tokenHash, { expirationTtl: LIMITS.ttlSec }),
    env.DB.put(`rl:${ip}`, '1', { expirationTtl: LIMITS.perIpCooldownSec }),
    env.DB.put(`rd:${day}:${ip}`, String(ipDay + 1), { expirationTtl: 2 * 24 * 3600 }),
    env.DB.put(`ad:${day}`, String(allDay + 1), { expirationTtl: 2 * 24 * 3600 }),
  ]);
  return json({ id });
}

export function validateInquiry(p, files = []) {
  if (!p || typeof p !== 'object') return 'bad payload';
  if (!CATEGORIES[p.category]) return 'bad category';
  const t = typeof p.text === 'string' ? p.text.trim() : '';
  if (t.length < LIMITS.minText || t.length > LIMITS.maxText) return 'bad text';
  if (typeof p.token !== 'string' || !/^[A-Za-z0-9_-]{32,64}$/.test(p.token)) return 'bad token';
  const i = p.info;
  if (!i || typeof i !== 'object') return 'bad info';
  for (const k of ['name', 'uuid', 'modVersion', 'gameVersion', 'server', 'place']) {
    if (typeof i[k] !== 'string' || i[k].length > 100) return `bad info.${k}`;
  }
  if (!/^[A-Za-z0-9_]{1,16}$|^\?$/.test(i.name)) return 'bad name';
  if (files.length > LIMITS.maxFiles) return 'too many files';
  for (const f of files) {
    if (!IMAGE_TYPES[ext(f.name)] || f.size > LIMITS.maxFileBytes) return 'bad file';
  }
  return null;
}

/** 마이크로소프트(정품) 계정 UUID(4판)만 NameMC 로 확인할 수 있다 */
export function realAccount(uuid) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(uuid);
}

export function inquiryMessage(env, id, p, names = []) {
  const c = CATEGORIES[p.category];
  const i = p.info;
  const embed = {
    title: cut(`[${c.label}] ${i.name}`, 256),
    description: cut(p.text.trim(), 4000),
    color: c.color,
    timestamp: new Date().toISOString(),
    fields: [
      field('플레이어', `${i.name}\n${i.uuid}\n${realAccount(i.uuid) ? `[계정 확인](https://namemc.com/profile/${i.uuid})` : '오프라인 계정(확인 불가)'}`),
      field('버전', `띵메이트 ${i.modVersion}\n마인크래프트 ${i.gameVersion}`),
      field('접속', `${i.server}\n${i.place}`),
    ],
    footer: { text: `문의 번호 ${id}` },
  };
  if (names.length) embed.image = { url: `attachment://${names[0]}` };
  return {
    content: env.DEV_USER_ID ? `<@${env.DEV_USER_ID}> 새 문의가 들어왔어요 · ${c.label}` : `새 문의가 들어왔어요 · ${c.label}`,
    // 문의 내용에 @everyone·다른 사람 멘션이 있어도 울리지 않게: 개발자 한 사람만
    allowed_mentions: { parse: [], users: env.DEV_USER_ID ? [env.DEV_USER_ID] : [] },
    embeds: [embed],
    components: [{ type: 1, components: [{ type: 2, style: 1, label: '답변 보내기', emoji: { name: '💬' }, custom_id: `reply:${id}` }] }],
  };
}

// ---------------------------------------------------------------- 디스코드 단추·입력창

async function interactions(request, env, ctx) {
  const body = await request.text();
  const ok = await verifyDiscord(env.DISCORD_PUBLIC_KEY, request.headers.get('x-signature-ed25519'),
    request.headers.get('x-signature-timestamp'), body);
  if (!ok) return text('bad signature', 401);
  const it = JSON.parse(body);
  if (it.type === 1) return json({ type: 1 }); // PING

  const user = (it.member && it.member.user) || it.user || {};
  const customId = (it.data && it.data.custom_id) || '';
  if (user.id !== env.DEV_USER_ID) return ephemeral('개발자만 답변할 수 있어요.');

  if (it.type === 3 && customId.startsWith('reply:')) {
    const id = customId.slice(6);
    const record = await recordById(env, id);
    if (!record) return ephemeral('이 문의 기록을 찾을 수 없어요(90일이 지났거나 지워졌어요).');
    return json({
      type: 9,
      data: {
        custom_id: `send:${id}`,
        title: cut(`${record.name} 님에게 답변`, 45),
        components: [{ type: 1, components: [{
          type: 4, custom_id: 'text', label: '답변 (띵메이트 채팅·알림으로 가요)', style: 2,
          min_length: 1, max_length: LIMITS.maxReply, required: true,
        }] }],
      },
    });
  }

  if (it.type === 5 && customId.startsWith('send:')) {
    const id = customId.slice(5);
    const reply = modalValue(it.data, 'text').trim();
    if (!reply) return ephemeral('답변이 비어 있어요.');
    const tokenHash = await env.DB.get(`id:${id}`);
    const record = tokenHash && parseJson(await env.DB.get(`t:${tokenHash}`));
    if (!record) return ephemeral('이 문의 기록을 찾을 수 없어요(90일이 지났거나 지워졌어요).');
    const n = record.replies.length + 1;
    record.replies.push({ n, text: reply, at: Date.now(), delivered: 0, noteId: null });
    await env.DB.put(`t:${tokenHash}`, JSON.stringify(record), { expirationTtl: LIMITS.ttlSec });
    // 3초 안에 답해야 해서 채널 기록은 뒤에서 남긴다
    ctx.waitUntil(postReplyNote(env, tokenHash, record, n));
    return ephemeral(`답변을 저장했어요. ${record.name} 님이 띵메이트를 켜고 접속하면 받아요.`);
  }
  return ephemeral('알 수 없는 동작이에요.');
}

async function postReplyNote(env, tokenHash, record, n) {
  const r = record.replies[n - 1];
  const res = await discord(env, 'POST', `/channels/${env.CHANNEL_ID}/messages`, {
    content: replyNote(record, r),
    allowed_mentions: { parse: [] },
    message_reference: { message_id: record.messageId, fail_if_not_exists: false },
  });
  if (!res.ok) return console.error('reply note failed', res.status, res.body);
  // 그 사이 이미 읽었을 수도 있으니 다시 읽어서 표시 자리만 붙인다
  const fresh = parseJson(await env.DB.get(`t:${tokenHash}`)) || record;
  if (fresh.replies[n - 1]) {
    fresh.replies[n - 1].noteId = res.body.id;
    await env.DB.put(`t:${tokenHash}`, JSON.stringify(fresh), { expirationTtl: LIMITS.ttlSec });
    if (fresh.replies[n - 1].delivered) await markNote(env, fresh, fresh.replies[n - 1]);
  }
}

export function replyNote(record, r) {
  const quoted = r.text.split('\n').map((l) => `> ${l}`).join('\n');
  const state = r.delivered ? `✅ 읽음 <t:${Math.floor(r.delivered / 1000)}:R>` : '⏳ 아직 안 읽음(접속하면 받아요)';
  return cut(`💬 **${record.name}** 님에게 답변 #${r.n}\n${quoted}\n${state}`, 2000);
}

async function markNote(env, record, r) {
  if (!r.noteId) return;
  await discord(env, 'PATCH', `/channels/${env.CHANNEL_ID}/messages/${r.noteId}`, { content: replyNote(record, r), allowed_mentions: { parse: [] } });
}

// ---------------------------------------------------------------- 띵메이트가 답변 받아 가기

async function replies(request, env, ctx) {
  const p = parseJson(await request.text());
  const tokens = p && Array.isArray(p.tokens) ? p.tokens : null;
  if (!tokens || tokens.length > LIMITS.maxTokensPerPoll || !tokens.every((t) => typeof t === 'string' && /^[A-Za-z0-9_-]{32,64}$/.test(t))) {
    return json({ error: 'bad tokens' }, 400);
  }
  const out = [];
  const marks = [];
  for (const token of tokens) {
    const hash = await sha256(token);
    const record = parseJson(await env.DB.get(`t:${hash}`));
    if (!record) {
      out.push({ found: false, replies: [] });
      continue;
    }
    out.push({ found: true, id: record.id, replies: record.replies.map((r) => ({ n: r.n, text: r.text, at: r.at })) });
    const now = Date.now();
    const fresh = record.replies.filter((r) => !r.delivered);
    if (fresh.length) {
      for (const r of fresh) r.delivered = now;
      marks.push(env.DB.put(`t:${hash}`, JSON.stringify(record), { expirationTtl: LIMITS.ttlSec })
        .then(() => Promise.all(fresh.map((r) => markNote(env, record, r)))));
    }
  }
  if (marks.length) ctx.waitUntil(Promise.all(marks));
  return json({ tickets: out });
}

// ---------------------------------------------------------------- 도움 함수

async function recordById(env, id) {
  if (!/^[0-9a-f]{8}$/.test(id)) return null;
  const hash = await env.DB.get(`id:${id}`);
  return hash ? parseJson(await env.DB.get(`t:${hash}`)) : null;
}

function modalValue(data, id) {
  for (const row of (data && data.components) || []) {
    for (const c of row.components || []) if (c.custom_id === id) return String(c.value || '');
  }
  return '';
}

async function discord(env, method, path, payload, files = [], names = []) {
  const init = { method, headers: { authorization: `Bot ${env.DISCORD_TOKEN}` } };
  if (files.length) {
    const form = new FormData();
    form.append('payload_json', JSON.stringify(payload));
    files.forEach((f, k) => form.append(`files[${k}]`, new Blob([f], { type: IMAGE_TYPES[ext(f.name)] }), names[k]));
    init.body = form;
  } else {
    init.headers['content-type'] = 'application/json';
    init.body = JSON.stringify(payload);
  }
  const res = await fetch(API + path, init);
  let body = null;
  try {
    body = await res.json();
  } catch (e) {
    body = null;
  }
  return { ok: res.ok, status: res.status, body };
}

export async function verifyDiscord(publicKeyHex, signatureHex, timestamp, body) {
  if (!publicKeyHex || !signatureHex || !timestamp) return false;
  try {
    const key = await crypto.subtle.importKey('raw', hex(publicKeyHex), { name: 'Ed25519' }, false, ['verify']);
    return await crypto.subtle.verify({ name: 'Ed25519' }, key, hex(signatureHex), new TextEncoder().encode(timestamp + body));
  } catch (e) {
    return false;
  }
}

function hex(s) {
  if (!/^([0-9a-f]{2})+$/i.test(s)) throw new Error('bad hex');
  return new Uint8Array(s.match(/../g).map((b) => parseInt(b, 16)));
}

export async function sha256(s) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function randomId() {
  return [...crypto.getRandomValues(new Uint8Array(4))].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function ext(name) {
  const n = String(name || '');
  const dot = n.lastIndexOf('.');
  return dot < 0 ? '' : n.slice(dot + 1).toLowerCase();
}

function field(name, value) {
  return { name, value: cut(value, 1000), inline: true };
}

function cut(s, max) {
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

function parseJson(s) {
  try {
    return typeof s === 'string' ? JSON.parse(s) : null;
  } catch (e) {
    return null;
  }
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json; charset=utf-8' } });
}

function text(s, status = 200) {
  return new Response(s, { status, headers: { 'content-type': 'text/plain; charset=utf-8' } });
}

function ephemeral(content) {
  return json({ type: 4, data: { content, flags: 64 } });
}
