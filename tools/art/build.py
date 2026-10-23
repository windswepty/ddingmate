"""HUD 세트 그림을 모드 리소스로 내보낸다: python -I tools/art/build.py

- assets/ddingmate/textures/gui/sprites/hud/panel_<id>.png(+.mcmeta 나인슬라이스)
- assets/ddingmate/textures/gui/sprites/hud/head_<id>.png(+.mcmeta)
- assets/ddingmate/textures/gui/sprites/hud/mascot_<id>.png
- assets/ddingmate/hud_skins.json (이름·글자색 등, Java 가 읽음)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mascots  # noqa: E402
import skins as S  # noqa: E402
import skins_more  # noqa: E402,F401  지역·계절·사람·전문가·시간대 판(SKINS 에 더한다)
import hd_sets  # noqa: E402  HD(2배 해상도) 세트: 같은 id 의 판·제목 줄·친구 그림을 이걸로(mcmeta 논리 크기는 그대로)
import hd_more  # noqa: E402  HD 세트 나머지 13명

HD_SETS = {**hd_sets.SETS, **hd_more.SETS}
HD_MASCOTS = {**hd_sets.MASCOTS, **hd_more.MASCOTS}

RES = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'ddingmate')
SPR = os.path.join(RES, 'textures', 'gui', 'sprites', 'hud')


def mcmeta(path, w, h, border):
    with open(path + '.mcmeta', 'w', encoding='utf-8', newline='\n') as f:
        json.dump({'gui': {'scaling': {'type': 'nine_slice', 'width': w, 'height': h, 'border': border}}}, f, indent=2)
        f.write('\n')


def main():
    os.makedirs(SPR, exist_ok=True)
    roles = {m[0]: m for m in mascots.ALL}
    out = []
    for sk in S.SKINS:
        panel, head = HD_SETS[sk['id']]() if sk['id'] in HD_SETS else S.build(sk)
        p = os.path.join(SPR, 'panel_%s.png' % sk['id'])
        panel.save(p)
        mcmeta(p, S.PW, S.PW, S.B)
        hp = os.path.join(SPR, 'head_%s.png' % sk['id'])
        head.save(hp)
        mcmeta(hp, S.PW, S.HH, {'left': 10, 'top': 0, 'right': 10, 'bottom': 0})
        m = roles.get(sk['id'])
        if sk['hasMascot']:
            fn = HD_MASCOTS.get(sk['id'], m[3])
            fn().render().save(os.path.join(SPR, 'mascot_%s.png' % sk['id']))
        out.append({'id': sk['id'], 'name': sk['name'], 'role': m[2] if m else sk['role'], 'place': sk['place'], 'dark': sk['dark'],
                    'title': sk['title'], 'titleShadow': sk['titleShadow'], 'colors': sk['colors'],
                    'group': sk['group'], 'hasMascot': sk['hasMascot']})
    meta = {'outset': S.O, 'headHeight': S.HH, 'mascotSize': mascots.W, 'pawRow': mascots.PAW_ROW, 'skins': out}
    with open(os.path.join(RES, 'hud_skins.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print(len(out), 'skins')


if __name__ == '__main__':
    main()
