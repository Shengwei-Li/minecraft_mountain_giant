"""
Mod icon (512x512) for Modrinth / CurseForge / the in-game mod list.
Minimal pixel art on a 32x32 grid: a mountain with a face - snow on its peak, moss on its shoulders,
two glowing eyes and the diamond core - standing in a band of mist.

python tools/make_icon.py            -> docs/images/icon.png
python tools/make_icon.py install    -> also mod/src/main/resources/logo.png
"""
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
N = 32
SCALE = 16

PAL = {
    ".": (27, 35, 56),     # night
    "L": (138, 142, 152),  # stone, lit side
    "s": (104, 108, 120),  # stone
    "S": (72, 76, 90),     # stone, shadow side
    "d": (46, 50, 62),     # the cracks between arms and body
    "W": (232, 238, 242),  # snow
    "G": (106, 150, 64),   # moss
    "g": (74, 112, 48),    # moss in shadow
    "E": (255, 214, 92),   # eyes
    "C": (96, 236, 226),   # core
    "m": (52, 64, 92),     # mist, far
    "M": (74, 88, 120),    # mist, near
}

# the silhouette's top row for each column (None = empty): arms, shoulders, a peak for the head
TOP = {4: 17, 5: 14, 6: 12, 7: 11, 8: 10, 9: 10, 10: 11, 11: 10, 12: 8, 13: 7, 14: 6, 15: 5,
       16: 5, 17: 6, 18: 7, 19: 8, 20: 10, 21: 11, 22: 10, 23: 10, 24: 11, 25: 12, 26: 14, 27: 17}

grid = [["."] * N for _ in range(N)]
for x, top in TOP.items():
    for y in range(top, N):
        edge = y == top
        if x <= 15:
            c = "L" if edge or x == min(TOP) or TOP.get(x - 1, 99) > y else "s"
        else:
            c = "s" if edge else "S"
        grid[y][x] = c
    # snow on the peak, moss on the shoulders
    if top <= 7:
        grid[top][x] = "W"
        if top <= 6:
            grid[top + 1][x] = "W"
    elif top <= 12:
        grid[top][x] = "G" if x <= 15 else "g"
        if x in (8, 9, 22, 23):
            grid[top + 1][x] = "G" if x <= 15 else "g"

# arms hang apart from the body
for y in range(17, N):
    grid[y][8] = "d"
    grid[y][23] = "d"
# eyes and the core
for x in (13, 14, 17, 18):
    grid[13][x] = "E"
for y in (18, 19):
    for x in (15, 16):
        grid[y][x] = "C"

# mist: two flat bands with a stepped top edge
for x in range(N):
    far = 24 + (1 if x % 7 in (0, 1, 2) else 0) - (1 if x % 11 in (4, 5, 6, 7) else 0)
    near = 28 + (1 if x % 5 in (3, 4) else 0) - (1 if x % 9 in (0, 1, 2) else 0)
    for y in range(far, N):
        grid[y][x] = "m"
    for y in range(near, N):
        grid[y][x] = "M"

img = Image.new("RGB", (N, N))
for y in range(N):
    for x in range(N):
        img.putpixel((x, y), PAL[grid[y][x]])
out = img.resize((N * SCALE, N * SCALE), Image.NEAREST)
os.makedirs(os.path.join(ROOT, "docs", "images"), exist_ok=True)
out.save(os.path.join(ROOT, "docs", "images", "icon.png"))
if "install" in sys.argv:
    out.save(os.path.join(ROOT, "mod", "src", "main", "resources", "logo.png"))
print("docs/images/icon.png")
