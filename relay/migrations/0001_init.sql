-- 문의: 번호표는 해시만 저장한다
CREATE TABLE IF NOT EXISTS tickets (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  category TEXT NOT NULL,
  created INTEGER NOT NULL,
  message_id TEXT
);

-- 답변: 문의마다 1, 2, 3 … 번호. 같은 디스코드 제출(interaction_id)은 한 번만 들어간다
CREATE TABLE IF NOT EXISTS replies (
  ticket_id TEXT NOT NULL,
  n INTEGER NOT NULL,
  text TEXT NOT NULL,
  at INTEGER NOT NULL,
  delivered INTEGER NOT NULL DEFAULT 0,
  note_id TEXT,
  interaction_id TEXT UNIQUE,
  PRIMARY KEY (ticket_id, n)
);

-- 도배 막기: 키 → 횟수, 끝나는 때
CREATE TABLE IF NOT EXISTS limits (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  until INTEGER NOT NULL
);
