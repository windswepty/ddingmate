-- /업데이트공지에 붙인 jar: 입력창을 채우는 동안 첨부 주소를 잠깐 기억(pending_files, 하루 뒤 지움)하고,
-- 보관한 파일 목록(downloads). 파일 자체는 Workers KV(FILES)에 jar:버전 으로 둔다
CREATE TABLE pending_files (id TEXT PRIMARY KEY, url TEXT NOT NULL, filename TEXT NOT NULL, size INTEGER NOT NULL, at INTEGER NOT NULL);
CREATE TABLE downloads (version TEXT PRIMARY KEY, filename TEXT NOT NULL, size INTEGER NOT NULL, at INTEGER NOT NULL);
