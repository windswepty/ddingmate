# 띵메이트 문의 중계 (Cloudflare Worker)

띵메이트 [문의] 탭 → 이 Worker → 디스코드 채널(임베드 + **[답변 보내기]** 단추)
→ 개발자가 단추로 답변 → 플레이어의 띵메이트가 접속할 때·10분마다 받아 가서 채팅·팝업으로 보여 줌.

- 게임 서버로는 아무것도 보내지 않는다.
- 문의마다 띵메이트가 만든 비밀 번호표(token)의 **해시만** 저장한다. 답변은 그 번호표를 가진 컴퓨터만 받는다.
- 저장은 D1(쓰자마자 정확히 읽힘). 기록은 하루 한 번 90일 지난 것을 지운다(cron).
  처음엔 KV 였는데 KV 는 최대 60초 예전 값을 읽어 같은 답변 기록이 채널에 두 번 달렸다(2026-10-18).
- 막는 것: 같은 주소 60초에 1번·하루 20번, 전체 하루 300번, 내용 5~1500자, 이미지 3장·8MB, 멘션은 개발자 한 사람만.

## 처음 한 번 설정

1. **디스코드 앱 만들기** — https://discord.com/developers/applications → New Application
   - General Information: `PUBLIC KEY` 복사
   - Bot: Reset Token → 토큰 복사(남에게 보여 주지 말 것). Privileged Intents 는 필요 없음
   - OAuth2 → URL Generator: scope `bot`, 권한 View Channels · Send Messages · Embed Links · Attach Files · Read Message History → 나온 주소로 내 서버에 초대
   - 디스코드 설정 → 고급 → 개발자 모드 켜기 → 문의 채널 우클릭 → 채널 ID 복사
2. **Cloudflare** — 무료 가입 후 이 폴더에서
   ```
   npm install
   npx wrangler login
   npx wrangler d1 create ddingmate-relay                       # 나온 database_id 를 wrangler.toml 에
   npx wrangler d1 migrations apply ddingmate-relay --remote    # 표 만들기
   ```
   `wrangler.toml` 의 `CHANNEL_ID` 에 채널 ID, `DISCORD_PUBLIC_KEY` 에 앱 공개 키.
3. **비밀 값**(터미널에 직접 붙여 넣기)
   ```
   npx wrangler secret put DISCORD_TOKEN
   ```
4. **올리기** — `npx wrangler deploy` → `https://ddingmate-relay.<이름>.workers.dev`
5. 디스코드 앱 General Information → **Interactions Endpoint URL** 에 `https://ddingmate-relay.<이름>.workers.dev/interactions` 저장
   (디스코드가 서명 확인을 해 보고 통과해야 저장됨)
6. 띵메이트 `src/main/resources/assets/ddingmate/inquiry.properties`(저장소에 안 올림)에 `relay=https://ddingmate-relay.<이름>.workers.dev`

## 시험

```
npm test              # 디스코드 흉내 + 진짜 SQLite(D1 흉내)로 전체 흐름
node test/smoke.mjs   # 실제 Workers 실행기(wrangler dev)로 서명·D1·첨부
```

## 무료 한도(Workers Free)

요청 하루 10만 번, D1 읽기 하루 500만 행·쓰기 하루 10만 행. 문의 1건 ≈ 쓰기 4행, 답변 1건 ≈ 쓰기 3행,
띵메이트의 답변 확인은 답을 기다리는 문의가 있을 때만(접속 직후·10분마다, 30일까지) 읽기 몇 행씩.
한도를 넘으면 그날(한국 시간 오전 9시까지) 문의·답변 확인이 실패하고, 띵메이트는 다음 차례에 다시 묻는다.
