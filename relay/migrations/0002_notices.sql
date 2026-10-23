-- 개발자 공지: 디스코드 /공지 로 올리고 [공지 내리기]로 내린다. 띵메이트가 접속할 때·30분마다 받아 간다
CREATE TABLE IF NOT EXISTS notices (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  text TEXT NOT NULL,
  at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  interaction_id TEXT UNIQUE,
  message_id TEXT
);
CREATE INDEX IF NOT EXISTS notices_at ON notices (at);
