"""
Profile picture from mugshot2.png (project root): snaps the picture back onto its 34x34 pixel grid, cleans the
palette, and gives the stone face some character - moss on its crown, a crisp glowing eye, a faint cyan spark in the
dark one, and a night-blue halo that suits round avatar frames.

python tools/make_avatar.py  ->  docs/images/avatar.png (544x544)
"""
import os
from collections import deque

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CELL, OX, OY = 36.5, 7.5, 25.5 - 36.5   # the source's pixel grid
N = 34
SCALE = 16

src = np.asarray(Image.open(os.path.join(ROOT, "mugshot2.png")).convert("RGB")).astype(np.float32)
g = np.zeros((N, N, 3), np.float32)
for r in range(N):
    for c in range(N):
        y, x = int(OY + (r + 0.5) * CELL), int(OX + (c + 0.5) * CELL)
        if 0 <= y < src.shape[0] and 0 <= x < src.shape[1]:
            g[r, c] = np.median(src[max(0, y - 8):y + 8, max(0, x - 8):x + 8].reshape(-1, 3), 0)

# ---- background: flood fill from the corners over the near-black ----
bg = np.zeros((N, N), bool)
queue = deque([(0, 0), (0, N - 1), (N - 1, 0), (N - 1, N - 1)])
while queue:
    r, c = queue.popleft()
    if not (0 <= r < N and 0 <= c < N) or bg[r, c] or g[r, c].max() > 34:
        continue
    bg[r, c] = True
    queue.extend(((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)))

# ---- the eye (warm, saturated) vs the stone (grey) ----
sat = g.max(2) - g.min(2)
eye = (sat > 40) & ~bg
ey, ex = np.argwhere(eye).mean(0).round().astype(int)

# ---- stone: six cool greys ----
STONE = np.array([[22, 22, 30], [44, 46, 58], [74, 78, 92], [110, 114, 128], [152, 156, 170], [206, 210, 222]],
                 np.float32)
out = np.zeros((N, N, 3), np.float32)
lum = g.mean(2)
for r in range(N):
    for c in range(N):
        if bg[r, c] or eye[r, c]:
            continue
        out[r, c] = STONE[np.abs(STONE.mean(1) - lum[r, c]).argmin()]

# ---- moss on the crown: the top face of the upper ledges, with a few drips ----
MOSS = [(92, 150, 56), (122, 178, 70), (64, 112, 44)]
face = ~bg
for c in range(N):
    rows = np.nonzero(face[:, c])[0]
    if not len(rows):
        continue
    top = rows[0]
    if top > 16:
        continue
    k = (c * 7) % 3
    out[top, c] = MOSS[1 if k == 0 else 0]
    if (c * 5) % 4 == 1 and face[top + 1, c] and not eye[top + 1, c]:
        out[top + 1, c] = MOSS[2]
        if (c * 3) % 5 == 2 and face[top + 2, c]:
            out[top + 2, c] = MOSS[2]
# ---- the glowing eye, redrawn crisp: white core, gold, orange rim, four sparks ----
GOLD = {"core": (255, 250, 214), "hot": (255, 220, 70), "gold": (246, 170, 30), "rim": (178, 96, 20)}
out[eye] = STONE[0]  # an empty socket first, then a clean diamond of light in it
for r in range(ey - 3, ey + 4):
    for c in range(ex - 3, ex + 4):
        d = abs(r - ey) + abs(c - ex)
        if d <= 2:
            out[r, c] = GOLD["core"] if d == 0 else GOLD["hot"] if d == 1 else GOLD["gold"]
        elif d == 3 and (r == ey or c == ex):
            out[r, c] = GOLD["rim"]
for dr, dc in ((-4, 0), (4, 0), (0, -4), (0, 4)):
    rr, cc = ey + dr, ex + dc
    if 0 <= rr < N and 0 <= cc < N and not eye[rr, cc]:
        out[rr, cc] = GOLD["hot"]
        bg[rr, cc] = False

# ---- a faint cyan spark deep in the dark eye (the giant's diamond core showing through) ----
dark_eye_c = N - 1 - ex + 1          # mirror of the lit eye
dark = [(ey, dark_eye_c), (ey, dark_eye_c - 1)]
for rr, cc in dark:
    if 0 <= rr < N and 0 <= cc < N:
        out[rr, cc] = (70, 210, 220) if cc == dark_eye_c else (36, 110, 124)

# ---- background: night blue with a stepped round halo behind the head ----
cy, cx = (N - 1) / 2, (N - 1) / 2
for r in range(N):
    for c in range(N):
        if not bg[r, c]:
            continue
        d = np.hypot(r - cy, c - cx)
        out[r, c] = (38, 58, 92) if d < 15.5 else (28, 42, 70) if d < 17 else (20, 28, 48)
# a few stars in the corners
for r, c in ((3, 4), (5, 29), (29, 3), (30, 28), (2, 17)):
    if bg[r, c]:
        out[r, c] = (170, 186, 220)

img = Image.fromarray(out.astype(np.uint8), "RGB").resize((N * SCALE, N * SCALE), Image.NEAREST)
os.makedirs(os.path.join(ROOT, "docs", "images"), exist_ok=True)
path = os.path.join(ROOT, "docs", "images", "avatar.png")
img.save(path, optimize=True)
print(path, os.path.getsize(path) // 1024, "KB")
