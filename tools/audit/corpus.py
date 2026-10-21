"""모든 플레이 로그의 채팅 줄(서버 안내 + 사람 채팅 모두, 순서 유지)을 시험 재료로 뽑는다.
사용: python -I corpus.py <로그 폴더> <출력 파일>
출력: 한 줄에 하나, "시각\t파일\t내용"(내용 안의 줄바꿈은 \\n). 중복 줄도 그대로(순서 재현용)."""
import gzip
import os
import sys

logs, out = sys.argv[1], sys.argv[2]
files = sorted(f for f in os.listdir(logs) if f.endswith('.log.gz') or f == 'latest.log')
n = 0
with open(out, 'w', encoding='utf-8', newline='\n') as w:
    for f in files:
        p = os.path.join(logs, f)
        op = gzip.open if f.endswith('.gz') else open
        try:
            with op(p, 'rt', encoding='utf-8', errors='replace') as r:
                for line in r:
                    if '[CHAT] ' not in line or '[띵메이트]' in line:
                        continue
                    t = line[1:9] if line.startswith('[') else ''
                    msg = line.split('[CHAT] ', 1)[1].rstrip('\n').replace('\\', '\\\\')
                    w.write(t + '\t' + f + '\t' + msg + '\n')
                    n += 1
        except Exception as e:
            print('skip', f, e)
print(len(files), 'files', n, 'lines')
