-- 업데이트 공지(/업데이트공지): 공지 표에 종류와 새 버전 정보(버전·다운로드 주소·추가·수정·삭제, JSON)를 더한다
ALTER TABLE notices ADD COLUMN kind TEXT NOT NULL DEFAULT 'notice';
ALTER TABLE notices ADD COLUMN data TEXT;
