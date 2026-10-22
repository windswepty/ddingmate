"""HUD 친구 미리보기: python -I tools/art/preview_mascots.py <출력.png>"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw, ImageFont  # noqa: E402
import mascots  # noqa: E402

out = sys.argv[1]
K = 6
try:
    font = ImageFont.truetype(r'C:\Windows\Fonts\malgunbd.ttf', 22)
except OSError:
    font = ImageFont.load_default()
tiles = []
for mid, name, role, fn in mascots.ALL:
    im = fn().render()
    big = im.resize((im.width * K, im.height * K), Image.NEAREST)
    t = Image.new('RGBA', (big.width * 2 + 30, big.height + 44), (40, 40, 46, 255))
    t.paste((250, 242, 234, 255), (0, 40, big.width + 10, t.height))
    t.paste((22, 25, 31, 255), (big.width + 20, 40, t.width, t.height))
    t.alpha_composite(big, (5, 42))
    t.alpha_composite(big, (big.width + 25, 42))
    ImageDraw.Draw(t).text((8, 8), f'{name} · {role}', fill=(255, 220, 90), font=font)
    tiles.append(t)
cols = 3
tw, th = tiles[0].size
sheet = Image.new('RGBA', (cols * (tw + 8), ((len(tiles) + cols - 1) // cols) * (th + 8)), (12, 12, 14, 255))
for i, t in enumerate(tiles):
    sheet.alpha_composite(t, ((i % cols) * (tw + 8), (i // cols) * (th + 8)))
sheet.save(out)
print(sheet.size)
