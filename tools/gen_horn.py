"""
Mountain Horn generator (references/horn.png).

Builds:
  mountain_horn.bbmodel            Java Block/Item model, opens in Blockbench
  textures/mountain_horn.png       model texture, 1 texel per model unit
  textures/mountain_horn_icon.png  flat 16x16 inventory icon

python tools/gen_horn.py            -> the files above
python tools/gen_horn.py install    -> also writes the item models + textures into the mod
"""
import base64
import io
import json
import math
import os
import random
import sys
import uuid

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXW, TEXH = 64, 64
rng_uuid = random.Random(91)


def new_uuid():
    return str(uuid.UUID(int=rng_uuid.getrandbits(128), version=4))


# ---------------------------------------------------------------------------
# Geometry: a chain of segments curving up from the mouthpiece (left) to the bell (upper right).
# Each segment is a box in its own frame (u along the horn, v across it, z through it) rotated about Z
# at its joint - Java item models only allow one axis and 22.5 degree steps, so the curve is built from those.
# ---------------------------------------------------------------------------
CHAIN = [
    # material, length, angle, half-thickness
    ("gold", 2.0, -22.5, 1.25),   # mouthpiece
    ("bone", 4.0, -22.5, 1.5),
    ("bone", 3.0, 0.0, 2.0),
    ("gold", 2.0, 0.0, 2.5),      # the band round the middle
    ("bone", 4.0, 22.5, 2.5),
    ("bone", 4.0, 45.0, 3.0),
    ("gold", 2.0, 45.0, 3.75),    # collar under the bell
]
BELL_LEN, BELL_T, WALL = 3.0, 4.5, 1.0

parts = []  # (name, P, angle, u0, u1, v0, v1, z0, z1, mat, face overrides)


def part(name, p, a, u0, u1, v0, v1, z0, z1, mat, faces=None):
    parts.append((name, p, a, u0, u1, v0, v1, z0, z1, mat, faces or {}))


p = np.array([0.0, 0.0])
drip_rng = random.Random(4)
for i, (mat, length, a, t) in enumerate(CHAIN):
    # a little overlap into the next joint hides the seams on the outside of each bend
    part(f"seg{i}_{mat}", p.copy(), a, 0, length + 0.4, -t, t, -t, t, mat)
    if mat == "bone":
        # moss hanging off the underside
        for k in range(2):
            du = drip_rng.uniform(0.4, length - 1.0)
            dz = drip_rng.choice([-t + 0.2, t - 1.2, -0.5])
            drop = drip_rng.choice([1.0, 1.5, 2.0])
            part(f"moss{i}_{k}", p.copy(), a, du, du + 1, -t - drop, -t + 0.3, dz, dz + 1, "moss")
    p = p + length * np.array([math.cos(math.radians(a)), math.sin(math.radians(a))])

# the bell: a gold rim round a dark mouth
a = 45.0
L, T, W = BELL_LEN, BELL_T, WALL
part("bell_top", p.copy(), a, 0, L, T - W, T, -T, T, "gold", {"down": "dark"})
part("bell_bottom", p.copy(), a, 0, L, -T, -T + W, -T, T, "gold", {"up": "dark"})
part("bell_front", p.copy(), a, 0, L, -T + W, T - W, T - W, T, "gold", {"north": "dark"})
part("bell_back", p.copy(), a, 0, L, -T + W, T - W, -T, -T + W, "gold", {"south": "dark"})
part("bell_throat", p.copy(), a, -0.2, 0.6, -T + W, T - W, -T + W, T - W, "dark")
# moss creeping up over the collar
part("moss_collar", p.copy(), a, -2.0, -0.6, -T - 0.2, -T + 1.0, -1.5, 1.0, "moss")

# centre the whole thing on the block (8, 8, 8)
corners = []
for name, P, ang, u0, u1, v0, v1, z0, z1, mat, fo in parts:
    c, s = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    for uu in (u0, u1):
        for vv in (v0, v1):
            corners.append((P[0] + uu * c - vv * s, P[1] + uu * s + vv * c))
corners = np.array(corners)
shift = np.array([8.0, 8.0]) - (corners.min(0) + corners.max(0)) / 2

elements = []
for name, P, ang, u0, u1, v0, v1, z0, z1, mat, fo in parts:
    o = P + shift
    elements.append({
        "name": name, "mat": mat, "face_mats": fo,
        "from": [round(o[0] + u0, 3), round(o[1] + v0, 3), round(8 + z0, 3)],
        "to": [round(o[0] + u1, 3), round(o[1] + v1, 3), round(8 + z1, 3)],
        "origin": [round(o[0], 3), round(o[1], 3), 8.0],
        "angle": ang,
    })

# ---------------------------------------------------------------------------
# Texture: every face gets its own patch
# ---------------------------------------------------------------------------
PALETTE = {
    "bone": ((214, 206, 188), 7),
    "moss": ((88, 118, 44), 9),
    "gold": ((230, 180, 70), 8),
    "dark": ((42, 36, 34), 4),
}
MOSS = [(96, 126, 48), (74, 102, 36), (122, 148, 58)]
nr = np.random.default_rng(12)


def paint(mat, w, h, face):
    base, amp = PALETTE[mat]
    img = np.zeros((h, w, 3), np.float32) + np.array(base, np.float32)
    img += nr.normal(0, amp, (h, w, 1))
    if mat == "bone":
        # weathered: grey speckles, and patches of moss (thicker on top)
        for _ in range(max(1, w * h // 6)):
            x, y = nr.integers(0, w), nr.integers(0, h)
            img[y, x] -= nr.choice([14, 22, 30])
        patches = w * h // (5 if face == "up" else 9)
        for _ in range(patches):
            x, y = nr.integers(0, w), nr.integers(0, h)
            col = np.array(MOSS[nr.integers(0, len(MOSS))], np.float32)
            for dx, dy in ((0, 0), (1, 0), (0, 1)):
                if x + dx < w and y + dy < h and nr.random() < 0.8:
                    img[y + dy, x + dx] = col + nr.normal(0, 5)
    elif mat == "moss":
        for yy in range(h):
            for xx in range(w):
                img[yy, xx] = np.array(MOSS[nr.integers(0, len(MOSS))], np.float32)
    elif mat == "gold":
        if h > 1:
            img[0, :] += 26
            img[-1, :] -= 30
        if w > 1:
            img[:, 0] += 10
            img[:, -1] -= 14
        img += nr.choice([0, 0, 0, -18], (h, w, 1))  # a few dents
    if face == "down":
        img *= 0.85
    return np.clip(img, 0, 255)


faces = []
for e in elements:
    dx, dy, dz = (math.ceil(e["to"][i] - e["from"][i] - 1e-6) for i in range(3))
    dims = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}
    for f, (w, h) in dims.items():
        faces.append([e, f, max(1, w), max(1, h)])

faces.sort(key=lambda t: (-t[3], -t[2]))
x = y = shelf = 0
for fe in faces:
    w, h = fe[2], fe[3]
    if x + w > TEXW:
        x, y, shelf = 0, y + shelf, 0
    fe += [x, y]
    x += w
    shelf = max(shelf, h)
assert y + shelf <= TEXH, f"texture too small ({y + shelf} rows)"

tex = np.zeros((TEXH, TEXW, 4), np.float32)
for e, f, w, h, u, v in faces:
    e.setdefault("faces", {})[f] = [u, v, u + w, v + h]
    tex[v:v + h, u:u + w, :3] = paint(e["face_mats"].get(f, e["mat"]), w, h, f)
    tex[v:v + h, u:u + w, 3] = 255
tex_img = Image.fromarray(tex.astype(np.uint8), "RGBA")


# ---------------------------------------------------------------------------
# Flat 16x16 inventory icon: mouthpiece bottom-left, bell top-right, like the reference
# ---------------------------------------------------------------------------
def horn_icon():
    COLORS = {"bone": (218, 210, 192), "gold": (232, 182, 70), "dark": (40, 34, 32),
              "outline": (30, 26, 28)}
    # centre line: a quadratic curve that dips, then sweeps up into the bell
    P0, P1, P2 = np.array([1.3, 11.0]), np.array([7.2, 15.4]), np.array([12.4, 4.4])
    ts = np.linspace(0, 1, 400)
    line = ((1 - ts) ** 2)[:, None] * P0 + (2 * (1 - ts) * ts)[:, None] * P1 + (ts ** 2)[:, None] * P2

    def radius(t):
        return 1.25 + 1.45 * t ** 1.3 + (0.5 if t > 0.88 else 0.0)

    grid = [[None] * 16 for _ in range(16)]
    for py in range(16):
        for px in range(16):
            c = np.array([px + 0.5, py + 0.5])
            d = np.linalg.norm(line - c, axis=1)
            # past the end of the curve the bell is flat-faced: only count points "behind" the mouth
            k = int(np.argmin(d - np.array([radius(t) for t in ts])))
            t, dist = ts[k], d[k]
            if dist > radius(t):
                continue
            if t < 0.06 or 0.44 <= t <= 0.49 or t >= 0.88:
                mat = "gold"
                if t >= 0.95 and dist < radius(t) - 1.0:
                    mat = "dark"        # looking into the mouth of the bell
            else:
                mat = "bone"
            grid[py][px] = mat
    # moss over the bone, heavier on the underside, with a few drips hanging below
    for py in range(16):
        for px in range(16):
            if grid[py][px] == "bone":
                below = py + 1 < 16 and grid[py + 1][px] is None
                h = (px * 37 + py * 91) % 11
                if (below and h < 7) or h < 3:
                    grid[py][px] = "moss"
    for px, py in ((5, 12), (9, 12), (12, 10)):
        if grid[py][px] is None and grid[py - 1][px] in ("moss", "bone"):
            grid[py][px] = "moss"
    img = np.zeros((16, 16, 4), np.uint8)
    for py in range(16):
        for px in range(16):
            mat = grid[py][px]
            if mat is None:
                if any(0 <= py + oy < 16 and 0 <= px + ox < 16 and grid[py + oy][px + ox] is not None
                       for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    img[py, px] = (*COLORS["outline"], 255)
                continue
            col = MOSS[(px * 5 + py * 3) % 3] if mat == "moss" else COLORS[mat]
            up_left_empty = (py == 0 or grid[py - 1][px] is None) or (px == 0 or grid[py][px - 1] is None)
            down_right_empty = (py == 15 or grid[py + 1][px] is None) or (px == 15 or grid[py][px + 1] is None)
            k = 1.18 if up_left_empty else 0.8 if down_right_empty else 1.0
            if mat == "bone" and (px * 7 + py * 13) % 5 == 0:
                k *= 0.9
            img[py, px] = (*(min(255, int(ch * k)) for ch in col), 255)
    return Image.fromarray(img, "RGBA")


icon_img = horn_icon()

# ---------------------------------------------------------------------------
# Display transforms
# ---------------------------------------------------------------------------
DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, -90, 25], "translation": [0, 4, 1], "scale": [0.55, 0.55, 0.55]},
    "thirdperson_lefthand": {"rotation": [0, 90, -25], "translation": [0, 4, 1], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.1, 3.2, 1.1], "scale": [0.5, 0.5, 0.5]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.1, 3.2, 1.1], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [20, -30, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 10, 0], "scale": [0.8, 0.8, 0.8]},
}

# ---------------------------------------------------------------------------
# Write
# ---------------------------------------------------------------------------
os.makedirs(os.path.join(ROOT, "textures"), exist_ok=True)
tex_img.save(os.path.join(ROOT, "textures", "mountain_horn.png"))
icon_img.save(os.path.join(ROOT, "textures", "mountain_horn_icon.png"))

buf = io.BytesIO()
tex_img.save(buf, "PNG")
bb_elements = []
for e in elements:
    bb_elements.append({
        "name": e["name"], "box_uv": False, "rescale": False, "locked": False, "light_emission": 0,
        "render_order": "default", "allow_mirror_modeling": True,
        "from": e["from"], "to": e["to"], "autouv": 0, "color": 0, "origin": e["origin"],
        "rotation": [0, 0, e["angle"]],
        "faces": {f: {"uv": uv, "texture": 0} for f, uv in e["faces"].items()},
        "type": "cube", "uuid": new_uuid(),
    })
bbmodel = {
    "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
    "name": "mountain_horn",
    "parent": "",
    "ambientocclusion": True,
    "front_gui_light": False,
    "visible_box": [1, 1, 0],
    "variable_placeholders": "",
    "variable_placeholder_buttons": [],
    "unhandled_root_fields": {},
    "resolution": {"width": TEXW, "height": TEXH},
    "elements": bb_elements,
    "outliner": [el["uuid"] for el in bb_elements],
    "textures": [{
        "path": os.path.join(ROOT, "textures", "mountain_horn.png"), "name": "mountain_horn.png",
        "folder": "item", "namespace": "mountain_giant", "id": "0", "width": TEXW, "height": TEXH,
        "uv_width": TEXW, "uv_height": TEXH, "particle": True, "use_as_default": False, "layers_enabled": False,
        "sync_to_project": "", "render_mode": "default", "render_sides": "auto", "frame_time": 1,
        "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False, "visible": True,
        "internal": True, "saved": True, "uuid": new_uuid(),
        "source": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode(),
    }],
    "display": DISPLAY,
}
with open(os.path.join(ROOT, "mountain_horn.bbmodel"), "w", encoding="utf-8") as fp:
    json.dump(bbmodel, fp)

if "install" in sys.argv:
    item_model = {
        "credit": "Mountain Giant mod",
        "texture_size": [TEXW, TEXH],
        "textures": {"0": "mountain_giant:item/mountain_horn", "particle": "mountain_giant:item/mountain_horn"},
        "elements": [{
            "name": e["name"], "from": e["from"], "to": e["to"],
            "rotation": {"angle": e["angle"], "axis": "z", "origin": e["origin"]},
            "faces": {f: {"uv": [c * 16 / (TEXW if i % 2 == 0 else TEXH) for i, c in enumerate(uv)], "texture": "#0"}
                      for f, uv in e["faces"].items()},
        } for e in elements],
        "display": DISPLAY,
    }
    assets = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "mountain_giant")
    with open(os.path.join(assets, "models", "item", "mountain_horn_3d.json"), "w", encoding="utf-8") as fp:
        json.dump(item_model, fp, indent=1)
    flat = {"parent": "minecraft:item/generated", "textures": {"layer0": "mountain_giant:item/mountain_horn_icon"}}
    with open(os.path.join(assets, "models", "item", "mountain_horn.json"), "w", encoding="utf-8") as fp:
        json.dump({
            "loader": "neoforge:separate_transforms",
            "base": {"parent": "mountain_giant:item/mountain_horn_3d"},
            "perspectives": {"gui": flat, "ground": flat, "fixed": flat},
        }, fp, indent=1)
    tex_img.save(os.path.join(assets, "textures", "item", "mountain_horn.png"))
    icon_img.save(os.path.join(assets, "textures", "item", "mountain_horn_icon.png"))
    print("installed into the mod")
print(f"{len(elements)} elements, texture rows used: {y + shelf}/{TEXH}")
