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

## 개발자 공지

띵메이트 사용자 모두에게 공지를 보낸다. 띵메이트는 게임에 들어가 있을 때 접속 직후·30분마다(`NOTICE_POLL_MIN`)
`GET /notices` 로 최근 30일 공지(새것부터 5개)를 받아 처음 보는 것만 팝업·채팅(내 화면만)·기록 탭으로 알린다.
받아 갈 때 띵메이트 버전만 오고 닉네임·UUID 는 오지 않는다.

- 올리기: 디스코드에서 **/공지** → 제목·내용 입력창 → 저장(개발자 `DEV_USER_ID` 만, 다른 사람은 거절).
  문의 채널에 기록이 남고 그 아래 **[공지 내리기]** 를 누르면 더는 안 간다(이미 받은 사람 화면엔 남음).
- 새 버전: 디스코드에서 **/업데이트공지** → 버전(예: 0.3.3) · 추가 · 수정 · 삭제(한 줄에 하나) · 다운로드 주소(https, 비우면
  `wrangler.toml` 의 `DOWNLOAD_URL`). 그 버전보다 **예전 버전을 쓰는 띵메이트(0.3.2 부터)** 에만 "새 버전이 나왔어요"가
  팝업·채팅([바뀐 점 보기]·[다운로드])으로 뜨고, 받기 전까지 접속할 때마다 채팅으로 다시 알린다. 0.3.1 이하는 공지를 받는 기능이 없다.
- 업데이트 파일 보관(Modrinth 승인 전까지): **/업데이트공지** 의 `파일` 칸에 jar 를 붙이면 중계가 Workers KV(`FILES`)에 보관하고,
  다운로드 주소 칸을 비우면 `https://…/download/0.3.3` 이 된다 → 띵메이트 [다운로드]를 누르면 브라우저가 jar 를 바로 받는다(25MB 까지).
  처음 한 번: `npx wrangler kv namespace create FILES` → 나온 id 를 `wrangler.toml` 의 `[[kv_namespaces]]` 에 넣고 `#` 를 지운 뒤,
  아래 표 만들기·배포·명령 등록(파일 칸이 생긴 새 명령 모양)을 한다.
- 처음 한 번(또는 명령 모양을 바꿨을 때) `/공지` · `/업데이트공지` 명령 등록:
  ```
  npx wrangler d1 migrations apply ddingmate-relay --remote   # notices·업데이트 파일 표
  npx wrangler deploy
  curl -X POST https://ddingmate-relay.<이름>.workers.dev/admin/commands -H "x-admin-key: $(cat .admin-key)"
  ```
  열쇠는 `relay/.admin-key`(저장소에 안 올림), `wrangler.toml` 에는 그 SHA-256(`ADMIN_KEY_HASH`)만 둔다.

## 시험

```
npm test              # 디스코드 흉내 + 진짜 SQLite(D1 흉내)로 전체 흐름
node test/smoke.mjs   # 실제 Workers 실행기(wrangler dev)로 서명·D1·첨부
```

## 무료 한도(Workers Free)

요청 하루 10만 번, D1 읽기 하루 500만 행·쓰기 하루 10만 행. 문의 1건 ≈ 쓰기 4행, 답변 1건 ≈ 쓰기 3행,
띵메이트의 답변 확인은 답을 기다리는 문의가 있을 때만(접속 직후·10분마다, 30일까지) 읽기 몇 행씩.
공지 확인은 게임에 들어가 있는 띵메이트마다 접속 직후·30분마다 요청 1번(읽기 5행 이하). 사용자가 많아져 요청 한도에
가까워지면 `NOTICE_POLL_MIN` 만 늘려 배포하면 띵메이트가 다음 응답부터 그 간격을 쓴다.
한도를 넘으면 그날(한국 시간 오전 9시까지) 문의·답변 확인이 실패하고, 띵메이트는 다음 차례에 다시 묻는다.
