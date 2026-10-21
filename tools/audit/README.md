# 위키 대조·시험 재료 도구

모두 `python -I`로 실행합니다. 위키 글·로그·기록은 개인 자료나 서버 자료가 섞여 있어 저장소에 넣지 않고, `build/` 아래나 임시 폴더에만 둡니다.

| 도구 | 하는 일 |
|---|---|
| `crawl.py <출력 폴더>` | 위키(wiki.ddingtycoon.kr/ko) 전체 문서를 받아 문서마다 본문 글로 저장 |
| `recipes_diff.py <위키 폴더> <recipes.json>` | 위키의 항목·재료 표와 레시피를 전부 비교(문서마다 표 방향 자동 판단) |
| `prices_diff.py <위키 폴더> <자바 파일>` | 자바 소스의 ("이름", 숫자) 짝이 위키의 그 이름 근처에 있는지(상점가·커피 값 등) |
| `skills.py <위키 폴더> <출력 json>` | 전문가 문서 4개에서 스킬별 레벨 설명을 뽑음(판매가·제작 시간 % 대조용) |
| `corpus.py <로그 폴더> <출력 tsv>` | 플레이 로그의 채팅 줄을 시험 재료로(`build/testdata/chat-corpus*.tsv`) |

시험 재생(`ReplayClientGameTest`)은 `build/testdata`가 있으면 돕니다.

- `chat-corpus*.tsv`: 위 `corpus.py` 결과
- `ddingmate-screens.txt`: 예전 창 기록
- `record/*.jsonl`: 개발용 원본 기록(`DevRecord`). 테스트 프로필 `config/ddingmate-record.on` 파일이 있으면 게임이 `config/ddingmate-record/`에 남깁니다.

결과는 `build/testdata/replay-report.txt`에 남습니다. 서버 리소스팩 시험은 `DDINGMATE_SERVER_PACK=1`로 켭니다.
