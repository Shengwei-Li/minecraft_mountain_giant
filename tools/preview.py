"""
Tiny software renderer for .bbmodel files, mirroring Blockbench's math
(three.js Euler order ZYX, group rotation + keyframe rotation added per axis).

python tools/preview.py                                  -> preview/rest.png (giant, 4 views)
python tools/preview.py view 180 -5 90 -5                -> preview/view.png (yaw pitch pairs)
python tools/preview.py walk 0 0.8 1.6 2.4               -> preview/walk.png (animation frames)
python tools/preview.py --model mountain_hammer.bbmodel --span 56 --center 9 --out hammer view 200 25
"""
import base64
import io
import json
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def take_option(name, default):
    """--name value options may come first: --model mountain_hammer.bbmodel --span 60 --center 8"""
    if name in sys.argv:
        i = sys.argv.index(name)
        value = sys.argv[i + 1]
        del sys.argv[i:i + 2]
        return value
    return default


MODEL_FILE = take_option("--model", "mountain_giant.bbmodel")
SPAN = float(take_option("--span", "160"))
CENTER_Y = float(take_option("--center", "52"))
OUT_NAME = take_option("--out", "view")
model = json.load(open(os.path.join(ROOT, MODEL_FILE), encoding="utf-8"))
t0 = model["textures"][0]
TEX = np.asarray(Image.open(io.BytesIO(base64.b64decode(t0["source"].split(",", 1)[1]))).convert("RGBA")).astype(np.float32)
SCALE = TEX.shape[1] / model["resolution"]["width"]
elements = {e["uuid"]: e for e in model["elements"]}


def rot_matrix(deg):
    x, y, z = (math.radians(v) for v in deg)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx  # order ZYX


def affine(R, pivot, offset=(0, 0, 0)):
    M = np.eye(4)
    M[:3, :3] = R
    p = np.array(pivot, float)
    M[:3, 3] = p + np.array(offset, float) - R @ p
    return M


def sample_anim(anim, bone_uuid, channel, t):
    if not anim or bone_uuid not in anim["animators"]:
        return (0, 0, 0)
    kfs = sorted([k for k in anim["animators"][bone_uuid]["keyframes"] if k["channel"] == channel],
                 key=lambda k: k["time"])
    if not kfs:
        return (0, 0, 0)
    val = lambda k: tuple(float(k["data_points"][0][a]) for a in "xyz")
    if t <= kfs[0]["time"]:
        return val(kfs[0])
    for a, b in zip(kfs, kfs[1:]):
        if a["time"] <= t <= b["time"]:
            f = (t - a["time"]) / max(1e-9, b["time"] - a["time"])
            va, vb = val(a), val(b)
            return tuple(va[i] + (vb[i] - va[i]) * f for i in range(3))
    return val(kfs[-1])


FACE_CORNERS = {
    # corners (as (x,y,z) picks: 0=from 1=to) in order: uv(u1,v1), (u2,v1), (u2,v2), (u1,v2)
    "north": [(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)],
    "south": [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
    "east": [(1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)],
    "west": [(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)],
    "up": [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
    "down": [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)],
}


def collect_quads(anim=None, t=0.0):
    quads = []

    def walk(node, M):
        rot = [a + b for a, b in zip(node.get("rotation", [0, 0, 0]), sample_anim(anim, node["uuid"], "rotation", t))]
        pos = sample_anim(anim, node["uuid"], "position", t)
        Mg = M @ affine(rot_matrix(rot), node["origin"], pos)
        for ch in node["children"]:
            if isinstance(ch, dict):
                walk(ch, Mg)
            else:
                e = elements[ch]
                Me = Mg @ affine(rot_matrix(e.get("rotation", [0, 0, 0])), e["origin"])
                fr, to = e["from"], e["to"]
                for fname, face in e["faces"].items():
                    pts = []
                    for pick in FACE_CORNERS[fname]:
                        p = np.array([to[i] if pick[i] else fr[i] for i in range(3)] + [1.0])
                        pts.append((Me @ p)[:3])
                    u1, v1, u2, v2 = face["uv"]
                    uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
                    quads.append((np.array(pts), np.array(uvs, float), e.get("light_emission", 0)))

    # root-level cubes (Java block models have no groups) live in a pseudo group
    walk({"origin": [0, 0, 0], "uuid": "", "children": model["outliner"]}, np.eye(4))
    return quads


def render(quads, yaw, pitch, size=560, span=150, center_y=52):
    Ry = rot_matrix((0, yaw, 0))
    Rx = rot_matrix((pitch, 0, 0))
    V = Rx @ Ry
    light = np.array([0.4, 0.8, 0.45]); light /= np.linalg.norm(light)
    img = np.zeros((size, size, 3), np.float32)
    img[:] = (200, 220, 240)
    zbuf = np.full((size, size), -1e9)
    s = size / span
    for pts, uvs, emis in quads:
        n = np.cross(pts[1] - pts[0], pts[3] - pts[0])
        nl = np.linalg.norm(n)
        if nl < 1e-9:
            continue
        n /= nl
        shade = 1.0 if emis else 0.55 + 0.45 * max(0.0, float(n @ light)) + 0.1 * max(0.0, float(-n @ light))
        cp = (V @ pts.T).T
        sx = size / 2 + cp[:, 0] * s
        sy = size / 2 - (cp[:, 1] - center_y) * s
        for tri in ((0, 1, 2), (0, 2, 3)):
            xs, ys, zs = sx[list(tri)], sy[list(tri)], cp[list(tri), 2]
            tuv = uvs[list(tri)]
            x0, x1 = int(max(0, math.floor(xs.min()))), int(min(size - 1, math.ceil(xs.max())))
            y0, y1 = int(max(0, math.floor(ys.min()))), int(min(size - 1, math.ceil(ys.max())))
            if x1 < x0 or y1 < y0:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            d = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if abs(d) < 1e-9:
                continue
            w0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / d
            w1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / d
            w2 = 1 - w0 - w1
            m = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not m.any():
                continue
            z = w0 * zs[0] + w1 * zs[1] + w2 * zs[2]
            sub = zbuf[y0:y1 + 1, x0:x1 + 1]
            m &= z > sub
            if not m.any():
                continue
            u = (w0 * tuv[0, 0] + w1 * tuv[1, 0] + w2 * tuv[2, 0]) * SCALE
            v = (w0 * tuv[0, 1] + w1 * tuv[1, 1] + w2 * tuv[2, 1]) * SCALE
            ui = np.clip(u.astype(int), 0, TEX.shape[1] - 1)
            vi = np.clip(v.astype(int), 0, TEX.shape[0] - 1)
            col = TEX[vi, ui, :3] * shade
            m &= TEX[vi, ui, 3] > 127  # transparent texels (armour openings) let what's behind show
            sub[m] = z[m]
            img[y0:y1 + 1, x0:x1 + 1][m] = col[m]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def sheet(images):
    w = sum(i.width for i in images)
    out = Image.new("RGB", (w, images[0].height))
    x = 0
    for i in images:
        out.paste(i, (x, 0))
        x += i.width
    return out


os.makedirs(os.path.join(ROOT, "preview"), exist_ok=True)
if len(sys.argv) == 1:
    q = collect_quads()
    views = [(180, -8), (90, -8), (215, -12), (0, -8)]   # front, side(right), 3/4, back
    sheet([render(q, y, p) for y, p in views]).save(os.path.join(ROOT, "preview", "rest.png"))
    print("preview/rest.png")
elif sys.argv[1] == "view":
    # python tools/preview.py view yaw pitch [yaw pitch ...]
    q = collect_quads()
    vals = [float(x) for x in sys.argv[2:]]
    sheet([render(q, vals[i], vals[i + 1], 700, SPAN, CENTER_Y) for i in range(0, len(vals), 2)]).save(
        os.path.join(ROOT, "preview", OUT_NAME + ".png"))
    print(f"preview/{OUT_NAME}.png")
else:
    name = sys.argv[1]
    anim = next(a for a in model["animations"] if a["name"].endswith("." + name))
    times = [float(x) for x in sys.argv[2:]] or [0]
    yaw = 120 if name in ("walk", "death", "smash") else 200
    sheet([render(collect_quads(anim, t), yaw, -8) for t in times]).save(os.path.join(ROOT, "preview", f"{name}.png"))
    print(f"preview/{name}.png")
