// 띵메이트 문의 중계(Cloudflare Worker)
//
// 1) POST /inquiry      띵메이트 [문의 보내기] → 디스코드 채널에 임베드 + [답변 보내기] 단추로 올린다
// 2) POST /interactions 디스코드 단추·입력창(개발자만) → 답변을 저장하고 채널에 "답변 보냄"을 남긴다
// 3) POST /replies      띵메이트가 접속할 때·10분마다 자기 문의의 답변을 받아 간다(읽으면 채널에 "읽음" 표시)
// 4) GET  /notices      띵메이트가 접속할 때·30분마다 개발자 공지(최근 30일, 내리지 않은 것)를 받아 간다
//    공지는 디스코드에서 개발자만 /공지 → 입력창으로 올리고, 채널에 남는 기록의 [공지 내리기]로 내린다
//    /업데이트공지 는 새 버전·다운로드 주소·추가·수정·삭제를 받아, 그보다 예전 버전을 쓰는 띵메이트에만 "새 버전 받으세요"로 뜬다
// 5) POST /admin/commands  디스코드에 /공지 명령을 등록(처음 한 번). x-admin-key 의 해시가 ADMIN_KEY_HASH 와 같아야 한다
//
// 문의마다 띵메이트가 만든 비밀 번호표(token)를 함께 받고, 저장은 그 해시로만 한다.
// 답변은 번호표를 가진 그 컴퓨터만 받을 수 있다(닉네임·UUID 를 알아도 남의 답변은 못 본다).
//
// 환경 값(wrangler.toml / 비밀 값):
//   DB                  D1 데이터베이스(migrations/). 쓰자마자 정확히 읽혀서 답변 번호·읽음 표시가 꼬이지 않는다
//                       (처음엔 KV 였는데, KV 는 최대 60초 예전 값을 읽어 같은 답변 기록이 두 번 달렸다, 2026-10-18)
//   CHANNEL_ID          문의가 올라갈 디스코드 채널 ID
//   DEV_USER_ID         멘션·답변할 개발자 디스코드 사용자 ID
//   DISCORD_TOKEN       (비밀) 봇 토큰
//   DISCORD_PUBLIC_KEY  (비밀 아님, 그래도 secret 로 넣어도 됨) 앱 공개 키(16진수)
//   ADMIN_KEY_HASH      (비밀 아님) /admin/commands 를 부를 열쇠의 SHA-256. 열쇠 자체는 저장소에 두지 않는다
//   NOTICE_POLL_MIN     띵메이트가 공지를 다시 물을 간격(분, 10~180). 요청이 많아지면 늘린다

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
  // 공지: 제목·내용 길이, 띵메이트에 보여 줄 기간과 개수
  maxNoticeTitle: 60,
  maxNoticeText: 1000,
  noticeDays: 30,
  maxNotices: 5,
  maxVersion: 20,
  maxUrl: 300,
  maxListItems: 20,
};

const IMAGE_TYPES = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === 'POST' && url.pathname === '/inquiry') return await inquiry(request, env);
      if (request.method === 'POST' && url.pathname === '/interactions') return await interactions(request, env, ctx);
      if (request.method === 'POST' && url.pathname === '/replies') return await replies(request, env, ctx);
      if (request.method === 'GET' && url.pathname === '/notices') return await notices(env);
      if (request.method === 'POST' && url.pathname === '/admin/commands') return await registerCommands(request, env);
      if (request.method === 'GET' && url.pathname === '/') return text('ddingmate relay ok');
      return json({ error: 'not found' }, 404);
    } catch (e) {
      console.error('relay error', e && e.stack || e);
      return json({ error: 'server error' }, 500);
    }
  },
  // 하루 한 번: 90일 지난 문의·답변과 끝난 도배 막기 기록을 지운다
  async scheduled(event, env) {
    await cleanup(env, Date.now());
  },
};

export async function cleanup(env, now) {
  const old = now - LIMITS.ttlSec * 1000;
  await env.DB.batch([
    env.DB.prepare('DELETE FROM replies WHERE ticket_id IN (SELECT id FROM tickets WHERE created < ?)').bind(old),
    env.DB.prepare('DELETE FROM tickets WHERE created < ?').bind(old),
    env.DB.prepare('DELETE FROM limits WHERE until < ?').bind(now),
    env.DB.prepare('DELETE FROM notices WHERE at < ?').bind(old),
  ]);
}

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
  const now = Date.now();
  const day = new Date(now).toISOString().slice(0, 10);
  const nextDay = Date.parse(day) + 2 * 24 * 3600 * 1000;
  const lim = (key) => env.DB.prepare('SELECT count FROM limits WHERE key = ? AND until > ?').bind(key, now).first('count');
  if (await lim(`ip:${ip}`)) return json({ error: 'cooldown' }, 429);
  if ((await lim(`ipday:${day}:${ip}`) || 0) >= LIMITS.perIpDaily || (await lim(`day:${day}`) || 0) >= LIMITS.globalDaily) {
    return json({ error: 'daily limit' }, 429);
  }

  const tokenHash = await sha256(payload.token);
  if (await env.DB.prepare('SELECT 1 AS x FROM tickets WHERE token_hash = ?').bind(tokenHash).first('x')) return json({ error: 'token reused' }, 400);
  const id = randomId();

  const names = files.map((f, k) => `image${k + 1}.${ext(f.name)}`);
  const message = inquiryMessage(env, id, payload, names);
  const posted = await discord(env, 'POST', `/channels/${env.CHANNEL_ID}/messages`, message, files, names);
  if (!posted.ok) {
    console.error('discord post failed', posted.status, posted.body);
    return json({ error: 'discord' }, 502);
  }

  const bump = (key, until) => env.DB.prepare('INSERT INTO limits (key, count, until) VALUES (?1, 1, ?2) '
    + 'ON CONFLICT(key) DO UPDATE SET count = CASE WHEN until > ?3 THEN count + 1 ELSE 1 END, until = ?2').bind(key, until, now);
  await env.DB.batch([
    env.DB.prepare('INSERT INTO tickets (id, token_hash, name, category, created, message_id) VALUES (?, ?, ?, ?, ?, ?)')
      .bind(id, tokenHash, displayName(payload.info), payload.category, now, posted.body.id),
    bump(`ip:${ip}`, now + LIMITS.perIpCooldownSec * 1000),
    bump(`ipday:${day}:${ip}`, nextDay),
    bump(`day:${day}`, nextDay),
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
  // 자바 닉네임(영문·숫자·_) + 베드록 연동 이름(앞의 . 이나 * , 띄어쓰기). 멘션·서식 글자는 막는다
  if (!/^[.*]?[A-Za-z0-9_ ]{1,32}$|^\?$/.test(i.name)) return 'bad name';
  // 띵타이쿤 한글 닉네임(있을 때만): 한글·영문·숫자·_ 16자까지
  if (i.nick !== undefined && (typeof i.nick !== 'string' || !/^[\p{Script=Hangul}A-Za-z0-9_]{1,16}$/u.test(i.nick))) return 'bad nick';
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

/** "windswepty(멜트)" — 한글 닉네임이 없으면 계정 이름만 */
export function displayName(i) {
  return i.nick ? `${i.name}(${i.nick})` : i.name;
}

export function inquiryMessage(env, id, p, names = []) {
  const c = CATEGORIES[p.category];
  const i = p.info;
  const embed = {
    title: cut(`[${c.label}] ${displayName(i)}`, 256),
    description: cut(p.text.trim(), 4000),
    color: c.color,
    timestamp: new Date().toISOString(),
    fields: [
      field('플레이어', `${displayName(i)}\n${i.uuid}\n${realAccount(i.uuid) ? `[계정 확인](https://namemc.com/profile/${i.uuid})` : '오프라인 계정(확인 불가)'}`),
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
  if (user.id !== env.DEV_USER_ID) return ephemeral('개발자만 쓸 수 있어요.');

  // 공지: /공지 → 입력창 → 저장하고 채널에 기록([공지 내리기] 단추)
  if (it.type === 2 && it.data && it.data.name === NOTICE_COMMAND.name) return json(noticeModal());
  if (it.type === 2 && it.data && it.data.name === UPDATE_COMMAND.name) return json(updateModal());
  if (it.type === 5 && customId === 'notice:new') return await saveNotice(it, env, ctx);
  if (it.type === 5 && customId === 'update:new') return await saveUpdate(it, env, ctx);
  if (it.type === 3 && customId.startsWith('unnotice:')) return await takeDownNotice(customId.slice(9), env);

  if (it.type === 3 && customId.startsWith('reply:')) {
    const ticket = await ticketById(env, customId.slice(6));
    if (!ticket) return ephemeral('이 문의 기록을 찾을 수 없어요(90일이 지났거나 지워졌어요).');
    return json({
      type: 9,
      data: {
        custom_id: `send:${ticket.id}`,
        title: cut(`${ticket.name} 님에게 답변`, 45),
        components: [{ type: 1, components: [{
          type: 4, custom_id: 'text', label: '답변 (띵메이트 채팅·알림으로 가요)', style: 2,
          min_length: 1, max_length: LIMITS.maxReply, required: true,
        }] }],
      },
    });
  }

  if (it.type === 5 && customId.startsWith('send:')) {
    const reply = modalValue(it.data, 'text').trim();
    if (!reply) return ephemeral('답변이 비어 있어요.');
    const ticket = await ticketById(env, customId.slice(5));
    if (!ticket) return ephemeral('이 문의 기록을 찾을 수 없어요(90일이 지났거나 지워졌어요).');
    // 번호 매기기와 저장을 한 문장으로: 동시에 두 개가 와도 번호가 겹치지 않고, 같은 제출(디스코드 재전송)은 한 번만
    const n = await env.DB.prepare('INSERT INTO replies (ticket_id, n, text, at, interaction_id) '
      + 'SELECT ?1, COALESCE(MAX(n), 0) + 1, ?2, ?3, ?4 FROM replies WHERE ticket_id = ?1 ON CONFLICT DO NOTHING RETURNING n')
      .bind(ticket.id, reply, Date.now(), String(it.id || '')).first('n');
    if (!n) return ephemeral('이미 저장한 답변이에요.');
    // 3초 안에 답해야 해서 채널 기록은 뒤에서 남긴다
    ctx.waitUntil(postReplyNote(env, ticket, n, reply));
    return ephemeral(`답변을 저장했어요. ${ticket.name} 님이 띵메이트를 켜고 접속하면 받아요.`);
  }
  return ephemeral('알 수 없는 동작이에요.');
}

async function postReplyNote(env, ticket, n, reply) {
  const res = await discord(env, 'POST', `/channels/${env.CHANNEL_ID}/messages`, {
    content: replyNote(ticket.name, n, reply, 0),
    allowed_mentions: { parse: [] },
    message_reference: { message_id: ticket.message_id, fail_if_not_exists: false },
  });
  if (!res.ok) return console.error('reply note failed', res.status, res.body);
  // 그 사이 이미 읽었으면 바로 "읽음"으로 고친다(읽음 표시 쪽은 아직 기록 자리를 몰라 못 고쳤다)
  const delivered = await env.DB.prepare('UPDATE replies SET note_id = ? WHERE ticket_id = ? AND n = ? RETURNING delivered')
    .bind(res.body.id, ticket.id, n).first('delivered');
  if (delivered) await markNote(env, ticket.name, { n, text: reply, delivered, note_id: res.body.id });
}

export function replyNote(name, n, text, delivered) {
  const quoted = text.split('\n').map((l) => `> ${l}`).join('\n');
  const state = delivered ? `✅ 읽음 <t:${Math.floor(delivered / 1000)}:R>` : '⏳ 아직 안 읽음(접속하면 받아요)';
  return cut(`💬 **${name}** 님에게 답변 #${n}\n${quoted}\n${state}`, 2000);
}

async function markNote(env, name, r) {
  if (!r.note_id) return;
  await discord(env, 'PATCH', `/channels/${env.CHANNEL_ID}/messages/${r.note_id}`,
    { content: replyNote(name, r.n, r.text, r.delivered), allowed_mentions: { parse: [] } });
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
  const now = Date.now();
  for (const token of tokens) {
    const ticket = await env.DB.prepare('SELECT id, name FROM tickets WHERE token_hash = ?').bind(await sha256(token)).first();
    if (!ticket) {
      out.push({ found: false, replies: [] });
      continue;
    }
    const { results } = await env.DB.prepare('SELECT n, text, at, delivered FROM replies WHERE ticket_id = ? ORDER BY n').bind(ticket.id).all();
    out.push({ found: true, id: ticket.id, replies: results.map((r) => ({ n: r.n, text: r.text, at: r.at })) });
    if (results.some((r) => !r.delivered)) {
      // 처음 받아 가는 답변만 읽음으로(한 문장이라 동시에 받아 가도 한 번만 고친다)
      const { results: fresh } = await env.DB.prepare('UPDATE replies SET delivered = ? WHERE ticket_id = ? AND delivered = 0 RETURNING n, text, delivered, note_id')
        .bind(now, ticket.id).all();
      for (const r of fresh) marks.push(markNote(env, ticket.name, r));
    }
  }
  if (marks.length) ctx.waitUntil(Promise.all(marks));
  // 띵메이트가 다음에 물을 간격(초). 무료 한도에 가까워지면 wrangler.toml 의 POLL_SEC 만 늘려 배포하면 된다
  return json({ tickets: out, pollSec: Number(env.POLL_SEC) || 120 });
}

// ---------------------------------------------------------------- 개발자 공지

export const NOTICE_COMMAND = {
  name: '공지',
  description: '띵메이트 사용자 모두에게 공지를 보내요(개발자만)',
  type: 1,
  // 서버 관리자에게만 보이게(실제로 쓸 수 있는 건 DEV_USER_ID 한 사람)
  default_member_permissions: '0',
};

export const UPDATE_COMMAND = {
  name: '업데이트공지',
  description: '새 버전 출시를 띵메이트 사용자 모두에게 알려요(개발자만)',
  type: 1,
  default_member_permissions: '0',
};

export function updateModal() {
  const para = (id, label) => ({ type: 1, components: [{ type: 4, custom_id: id, label, style: 2, max_length: LIMITS.maxNoticeText, required: false }] });
  return {
    type: 9,
    data: {
      custom_id: 'update:new',
      title: '띵메이트 업데이트 공지',
      components: [
        { type: 1, components: [{ type: 4, custom_id: 'version', label: '새 버전 (예: 0.3.3)', style: 1, min_length: 3, max_length: LIMITS.maxVersion, required: true }] },
        para('added', '추가된 것 (한 줄에 하나)'),
        para('changed', '수정된 것 (한 줄에 하나)'),
        para('removed', '삭제된 것 (한 줄에 하나)'),
        { type: 1, components: [{ type: 4, custom_id: 'url', label: '다운로드 주소 (https://…, 비우면 기본 주소)', style: 1, max_length: LIMITS.maxUrl, required: false }] },
      ],
    },
  };
}

/** 여러 줄 입력 → 항목 목록(앞의 - · • * 와 빈 줄은 뺀다) */
export function listItems(text) {
  return String(text || '').split('\n').map((l) => l.replace(/^\s*[-•*·]\s*/, '').trim()).filter((l) => l)
    .slice(0, LIMITS.maxListItems).map((l) => cut(l, 200));
}

export function updateText(u) {
  const part = (head, list) => (list.length ? `${head}\n${list.map((l) => `- ${l}`).join('\n')}` : '');
  return [part('추가', u.added), part('수정', u.changed), part('삭제', u.removed)].filter((x) => x).join('\n') || '새 버전이 나왔어요';
}

async function saveUpdate(it, env, ctx) {
  const version = modalValue(it.data, 'version').trim();
  if (!/^\d+(\.\d+){1,3}$/.test(version)) return ephemeral('버전은 0.3.3 처럼 숫자와 점으로 적어 주세요.');
  let url = modalValue(it.data, 'url').trim() || env.DOWNLOAD_URL || '';
  if (url && !/^https:\/\/[^\s]+$/.test(url)) return ephemeral('다운로드 주소는 https:// 로 시작해야 해요.');
  const u = { version, url: cut(url, LIMITS.maxUrl), added: listItems(modalValue(it.data, 'added')),
    changed: listItems(modalValue(it.data, 'changed')), removed: listItems(modalValue(it.data, 'removed')) };
  const id = randomId();
  const title = `띵메이트 ${version} 출시`;
  const body = updateText(u);
  const saved = await env.DB.prepare('INSERT INTO notices (id, title, text, at, interaction_id, kind, data) VALUES (?, ?, ?, ?, ?, ?, ?) '
    + 'ON CONFLICT DO NOTHING RETURNING id').bind(id, title, cut(body, 2000), Date.now(), String(it.id || ''), 'update', JSON.stringify(u)).first('id');
  if (!saved) return ephemeral('이미 올린 업데이트 공지예요.');
  ctx.waitUntil(postNoticeNote(env, id, title, body + (u.url ? `\n다운로드: ${u.url}` : '')));
  return ephemeral(`업데이트 공지를 올렸어요. ${version} 보다 예전 버전을 쓰는 띵메이트에 "새 버전을 받아 주세요"로 떠요(0.3.2 부터).\n`
    + '채널에 남은 기록의 [공지 내리기]로 내릴 수 있어요.');
}

export function noticeModal() {
  return {
    type: 9,
    data: {
      custom_id: 'notice:new',
      title: '띵메이트 공지 보내기',
      components: [
        { type: 1, components: [{ type: 4, custom_id: 'title', label: '제목 (팝업·채팅 첫 줄)', style: 1, min_length: 1, max_length: LIMITS.maxNoticeTitle, required: true }] },
        { type: 1, components: [{ type: 4, custom_id: 'text', label: '내용 (띵메이트 채팅·알림 기록으로 가요)', style: 2, min_length: 1, max_length: LIMITS.maxNoticeText, required: true }] },
      ],
    },
  };
}

async function saveNotice(it, env, ctx) {
  const title = modalValue(it.data, 'title').trim();
  const body = modalValue(it.data, 'text').trim();
  if (!title || !body) return ephemeral('제목과 내용을 모두 적어 주세요.');
  if (title.length > LIMITS.maxNoticeTitle || body.length > LIMITS.maxNoticeText) return ephemeral('너무 길어요.');
  const id = randomId();
  const at = Date.now();
  // 같은 제출(디스코드 재전송)은 한 번만
  const saved = await env.DB.prepare('INSERT INTO notices (id, title, text, at, interaction_id) VALUES (?, ?, ?, ?, ?) '
    + 'ON CONFLICT DO NOTHING RETURNING id').bind(id, title, body, at, String(it.id || '')).first('id');
  if (!saved) return ephemeral('이미 올린 공지예요.');
  ctx.waitUntil(postNoticeNote(env, id, title, body));
  return ephemeral('공지를 올렸어요. 띵메이트 사용자가 접속할 때(또는 30분 안에) 받아요.\n채널에 남은 기록의 [공지 내리기]로 내릴 수 있어요.');
}

export function noticeNote(title, text, down) {
  const quoted = text.split('\n').map((l) => `> ${l}`).join('\n');
  const state = down ? '🗑️ 내린 공지(더는 받지 않아요. 이미 받은 사람 화면에는 남아요)' : '📢 띵메이트 사용자에게 보내는 중(최근 30일)';
  return cut(`📢 **공지 · ${title}**\n${quoted}\n${state}`, 2000);
}

async function postNoticeNote(env, id, title, body) {
  const res = await discord(env, 'POST', `/channels/${env.CHANNEL_ID}/messages`, {
    content: noticeNote(title, body, false),
    allowed_mentions: { parse: [] },
    components: [{ type: 1, components: [{ type: 2, style: 4, label: '공지 내리기', custom_id: `unnotice:${id}` }] }],
  });
  if (!res.ok) return console.error('notice note failed', res.status, res.body);
  await env.DB.prepare('UPDATE notices SET message_id = ? WHERE id = ?').bind(res.body.id, id).run();
}

async function takeDownNotice(id, env) {
  if (!/^[0-9a-f]{8}$/.test(id)) return ephemeral('알 수 없는 공지예요.');
  const n = await env.DB.prepare('UPDATE notices SET deleted = 1 WHERE id = ? RETURNING title, text').bind(id).first();
  if (!n) return ephemeral('이 공지 기록을 찾을 수 없어요(30일이 지났거나 지워졌어요).');
  // 단추가 달린 그 기록을 바로 고친다(단추는 뺀다)
  return json({ type: 7, data: { content: noticeNote(n.title, n.text, true), components: [], allowed_mentions: { parse: [] } } });
}

/** 띵메이트가 받아 갈 공지: 내리지 않은 최근 30일 것, 새것부터 5개. 닉네임 등은 받지 않는다 */
async function notices(env) {
  const since = Date.now() - LIMITS.noticeDays * 24 * 3600 * 1000;
  const { results } = await env.DB.prepare('SELECT id, title, text, at, kind, data FROM notices WHERE deleted = 0 AND at > ? ORDER BY at DESC LIMIT ?')
    .bind(since, LIMITS.maxNotices).all();
  const poll = Math.max(10, Math.min(180, Number(env.NOTICE_POLL_MIN) || 30));
  return json({
    notices: results.map((r) => {
      const n = { id: r.id, title: r.title, text: r.text, at: r.at, kind: r.kind || 'notice' };
      if (n.kind === 'update') Object.assign(n, parseJson(r.data) || {});
      return n;
    }),
    pollMin: poll,
  });
}

/** /공지 명령 등록(처음 한 번, 명령 모양을 바꿨을 때). 앱 번호는 봇 토큰으로 알아낸다 */
async function registerCommands(request, env) {
  const key = request.headers.get('x-admin-key') || '';
  if (!env.ADMIN_KEY_HASH || !key || await sha256(key) !== env.ADMIN_KEY_HASH) return json({ error: 'forbidden' }, 403);
  const app = await discord(env, 'GET', '/oauth2/applications/@me');
  if (!app.ok || !app.body || !app.body.id) return json({ error: 'app', status: app.status }, 502);
  const res = await discord(env, 'PUT', `/applications/${app.body.id}/commands`, [NOTICE_COMMAND, UPDATE_COMMAND]);
  return json({ ok: res.ok, status: res.status, commands: Array.isArray(res.body) ? res.body.map((c) => c.name) : res.body }, res.ok ? 200 : 502);
}

// ---------------------------------------------------------------- 도움 함수

async function ticketById(env, id) {
  if (!/^[0-9a-f]{8}$/.test(id)) return null;
  return env.DB.prepare('SELECT id, name, message_id FROM tickets WHERE id = ?').bind(id).first();
}

function modalValue(data, id) {
  for (const row of (data && data.components) || []) {
    for (const c of row.components || []) if (c.custom_id === id) return String(c.value || '');
  }
  return '';
}

async function discord(env, method, path, payload, files = [], names = []) {
  const init = { method, headers: { authorization: `Bot ${env.DISCORD_TOKEN}` } };
  if (payload === undefined) {
    // GET: 몸통 없음
  } else if (files.length) {
    const form = new FormData();
    form.append('payload_json', JSON.stringify(payload));
    files.forEach((f, k) => form.append(`files[${k}]`, new Blob([f], { type: IMAGE_TYPES[ext(f.name)] }), names[k]));
    init.body = form;
  } else {
    init.headers['content-type'] = 'application/json';
    init.body = JSON.stringify(payload);
  }
  const res = await fetch(API + path, init);
  console.log("discord", method, path, res.status);
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

export function parseJson(s) {
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
