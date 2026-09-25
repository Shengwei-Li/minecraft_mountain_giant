"""
Mountain Hammer + Mountain Heart generator (references/weapon.png, references/material.png).

Builds:
  mountain_hammer.bbmodel          Java Block/Item model, opens in Blockbench (Display tab shows hand/GUI poses)
  textures/mountain_hammer.png     128x64, 1 texel per model unit (same pixel scale as vanilla items)
  textures/mountain_heart.png      16x16 icon sampled from references/material.png
  mod/.../assets/mountain_giant/    item models + textures used by the mod

python tools/gen_hammer.py
"""
import base64
import io
import json
import math
import os
import random
import uuid

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXW, TEXH = 128, 64
rng_uuid = random.Random(77)


def new_uuid():
    return str(uuid.UUID(int=rng_uuid.getrandbits(128), version=4))


# ---------------------------------------------------------------------------
# Geometry (model units; 16 = one block). Hammer stands upright, head along X.
# ---------------------------------------------------------------------------
elements = []


def box(name, frm, to, mat):
    elements.append({"name": name, "from": list(frm), "to": list(to), "mat": mat})


# pommel: stone block with a gold collar and a gold stud on the end
box("pommel", (5, -12, 5), (11, -6, 11), "stone")
box("pommel_collar", (4.5, -7.5, 4.5), (11.5, -6, 11.5), "gold")
box("pommel_stud", (7, -12.5, 7), (9, -12, 9), "gold")
# handle with two gold bands
box("handle", (6, -6, 6), (10, 19, 10), "wood")
box("band_low", (5.5, -1, 5.5), (10.5, 1.5, 10.5), "gold")
box("band_high", (5.5, 11, 5.5), (10.5, 13.5, 10.5), "gold")
# head: two heavy end blocks and a slimmer middle
box("head_left", (-6, 17.5, 3), (3, 28.5, 13), "stone")
box("head_mid", (3, 19, 4), (13, 27, 12), "stone")
box("head_right", (13, 17.5, 3), (22, 28.5, 13), "stone")
# gold L-trims on the end blocks: a bar across the top by the inner edge, running down the front face
for side, (x0, x1) in (("left", (0.5, 2.5)), ("right", (13.5, 15.5))):
    box(f"trim_{side}_top", (x0, 28.5, 3), (x1, 29.5, 13), "gold")
    box(f"trim_{side}_front", (x0, 18.5, 2.5), (x1, 29.5, 3), "gold")
    box(f"trim_{side}_back", (x0, 18.5, 13), (x1, 29.5, 13.5), "gold")
# a blackened iron hoop round each end block, near its outer end
box("hoop_left", (-4.5, 17, 2.5), (-2.5, 29, 13.5), "iron")
box("hoop_right", (18.5, 17, 2.5), (20.5, 29, 13.5), "iron")
# the gold plate on the middle, with a strap running down to the handle
box("plate", (5.5, 27, 5.5), (10.5, 28, 10.5), "gold")
box("plate_boss", (6.5, 28, 6.5), (9.5, 28.5, 9.5), "gold_dark")
box("strap_front", (7.5, 19, 3.5), (8.5, 27, 4), "gold")
box("collar", (5.5, 17, 5.5), (10.5, 19, 10.5), "gold")

# ---------------------------------------------------------------------------
# Texture: every face gets its own patch, 1 texel per unit
# ---------------------------------------------------------------------------
PALETTE = {
    "stone": ((132, 128, 130), 10),
    "gold": ((232, 184, 76), 8),
    "gold_dark": ((200, 150, 52), 6),
    "wood": ((112, 76, 46), 8),
    "iron": ((40, 38, 44), 5),
}
nr = np.random.default_rng(5)


def paint(mat, w, h, face):
    base, amp = PALETTE[mat]
    img = np.zeros((h, w, 3), np.float32) + np.array(base, np.float32)
    img += nr.normal(0, amp, (h, w, 1))
    if mat == "stone":
        # darker and lighter blotches, like the reference's cobbled grey
        for _ in range(max(1, w * h // 12)):
            x, y = nr.integers(0, w), nr.integers(0, h)
            img[y, x] += nr.choice([-24, -16, 14, 20])
        img[:, :, 2] += 3  # slightly cool grey
        # soft block shading: lighter towards the top-left, darker towards the bottom-right
        gy = np.linspace(8, -10, h)[:, None, None] if h > 1 else 0
        gx = np.linspace(5, -5, w)[None, :, None] if w > 1 else 0
        img += gy + gx
    elif mat.startswith("gold"):
        # lit from the top-left: bright top edge, darker bottom edge
        if h > 1:
            img[0, :] += 28
            img[-1, :] -= 30
        if w > 1:
            img[:, 0] += 12
            img[:, -1] -= 14
    elif mat == "iron":
        # dull sheen along the top edge, rivets along the middle
        if h > 1:
            img[0, :] += 22
            img[-1, :] -= 10
        if w >= 4 and h >= 2:
            for rx in range(1, w - 1, 3):
                img[h // 2, rx] += 30
    elif mat == "wood":
        # vertical grain on the sides, rings on the ends
        if face in ("up", "down"):
            img[1:-1, 1:-1] -= 12
        else:
            for x in range(w):
                img[:, x] += nr.normal(0, 6)
            for _ in range(max(1, h // 4)):
                y = nr.integers(0, h)
                img[y, :] -= 14
    if face == "down":
        img *= 0.85
    elif face in ("north", "south"):
        img *= 0.97
    return np.clip(img, 0, 255)


faces = []
for e in elements:
    dx, dy, dz = (math.ceil(e["to"][i] - e["from"][i]) for i in range(3))
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
    tex[v:v + h, u:u + w, :3] = paint(e["mat"], w, h, f)
    tex[v:v + h, u:u + w, 3] = 255
tex_img = Image.fromarray(tex.astype(np.uint8), "RGBA")

# ---------------------------------------------------------------------------
# Mountain Heart icon: sample the pixel grid of references/material.png down to 16x16
# ---------------------------------------------------------------------------
ref = Image.open(os.path.join(ROOT, "references", "material.png")).convert("RGB")
arr = np.asarray(ref).astype(np.int32)
# the art is a ~46px pixel grid; find the drawn area and sample each cell's centre
mask = arr.sum(axis=2) > 90
ys, xs = np.nonzero(mask)
cell = 46.0
x0, y0 = xs.min(), ys.min()
cols = int(round((xs.max() - x0 + 1) / cell))
rows = int(round((ys.max() - y0 + 1) / cell))
icon = np.zeros((16, 16, 4), np.uint8)
ox, oy = (16 - cols) // 2 + 1, (16 - rows) // 2  # +1: sits centred in the slot
for r in range(rows):
    for c in range(cols):
        px, py = int(x0 + (c + 0.5) * cell), int(y0 + (r + 0.5) * cell)
        if 0 <= px < arr.shape[1] and 0 <= py < arr.shape[0] and mask[py, px]:
            icon[oy + r, ox + c, :3] = arr[py, px]
            icon[oy + r, ox + c, 3] = 255
heart_img = Image.fromarray(icon, "RGBA")


# ---------------------------------------------------------------------------
# Flat 16x16 inventory icon of the hammer (like vanilla weapons: handle bottom-left, head top-right)
# ---------------------------------------------------------------------------
def hammer_icon():
    COLORS = {
        "stone": (138, 134, 138), "iron": (44, 42, 48), "gold": (232, 182, 70),
        "wood": (118, 80, 48), "outline": (30, 27, 32),
    }
    SQ2 = math.sqrt(2.0)

    def material_at(px, py):
        # u runs along the handle towards the top-right, v across it
        dx, dy = px - 8.0, py - 8.0
        u, v = (dx - dy) / SQ2, (dx + dy) / SQ2
        av = abs(v)
        if 2.9 <= u <= 8.6 and av <= 6.3:                      # head
            if 4.2 <= av <= 5.2:
                return "iron"                                  # black hoops near the ends
            if 1.7 <= av <= 2.4:
                return "gold"                                  # gold trims
            if u >= 7.8 and av <= 1.0:
                return "gold"                                  # the plate on top
            return "stone"
        if -8.4 <= u < 2.9 and av <= 0.95:                     # handle
            if -5.4 <= u <= -4.4 or -1.2 <= u <= -0.2:
                return "gold"                                  # bands
            return "wood"
        if -10.4 <= u < -8.4 and av <= 1.5:                    # pommel with a gold ring
            return "gold" if u >= -9.0 else "stone"
        return None

    grid = [[material_at(x + 0.5, y + 0.5) for x in range(16)] for y in range(16)]
    img = np.zeros((16, 16, 4), np.uint8)
    for y in range(16):
        for x in range(16):
            mat = grid[y][x]
            if mat is None:
                # outline: a dark pixel wherever the shape touches empty space
                if any(0 <= y + oy < 16 and 0 <= x + ox < 16 and grid[y + oy][x + ox] is not None
                       for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    img[y, x] = (*COLORS["outline"], 255)
                continue
            r, g, b = COLORS[mat]
            # light from the top-left: brighten where the pixel above-left is outside the shape
            up_left_empty = (y == 0 or grid[y - 1][x] is None) or (x == 0 or grid[y][x - 1] is None)
            down_right_empty = (y == 15 or grid[y + 1][x] is None) or (x == 15 or grid[y][x + 1] is None)
            k = 1.22 if up_left_empty else 0.78 if down_right_empty else 1.0
            if mat == "stone" and (x * 7 + y * 13) % 5 == 0:
                k *= 0.9  # a little stone speckle
            img[y, x] = (min(255, int(r * k)), min(255, int(g * k)), min(255, int(b * k)), 255)
    return Image.fromarray(img, "RGBA")


hammer_icon_img = hammer_icon()

# ---------------------------------------------------------------------------
# Display transforms (how it sits in hand, GUI, item frame...)
# ---------------------------------------------------------------------------
DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [0, 5.5, 1], "scale": [0.6, 0.6, 0.6]},
    "thirdperson_lefthand": {"rotation": [0, -90, 0], "translation": [0, 5.5, 1], "scale": [0.6, 0.6, 0.6]},
    "firstperson_righthand": {"rotation": [0, -90, 15], "translation": [1.5, 3.5, 0.5], "scale": [0.5, 0.5, 0.5]},
    "firstperson_lefthand": {"rotation": [0, 90, -15], "translation": [1.5, 3.5, 0.5], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [20, -30, -45], "translation": [-0.5, -0.5, 0], "scale": [0.36, 0.36, 0.36]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.35, 0.35, 0.35]},
    "fixed": {"rotation": [0, 0, -45], "translation": [0, 0, 0], "scale": [0.45, 0.45, 0.45]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 10, 0], "scale": [0.6, 0.6, 0.6]},
}

# ---------------------------------------------------------------------------
# Write: bbmodel (for Blockbench), item model json + textures (for the mod)
# ---------------------------------------------------------------------------
os.makedirs(os.path.join(ROOT, "textures"), exist_ok=True)
tex_img.save(os.path.join(ROOT, "textures", "mountain_hammer.png"))
heart_img.save(os.path.join(ROOT, "textures", "mountain_heart.png"))

buf = io.BytesIO()
tex_img.save(buf, "PNG")
bb_elements = []
for e in elements:
    center = [(e["from"][i] + e["to"][i]) / 2 for i in range(3)]
    bb_elements.append({
        "name": e["name"], "box_uv": False, "rescale": False, "locked": False, "light_emission": 0,
        "render_order": "default", "allow_mirror_modeling": True,
        "from": e["from"], "to": e["to"], "autouv": 0, "color": 0, "origin": center,
        "faces": {f: {"uv": uv, "texture": 0} for f, uv in e["faces"].items()},
        "type": "cube", "uuid": new_uuid(),
    })
bbmodel = {
    "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
    "name": "mountain_hammer",
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
        "path": os.path.join(ROOT, "textures", "mountain_hammer.png"), "name": "mountain_hammer.png",
        "folder": "item", "namespace": "mountain_giant", "id": "0", "width": TEXW, "height": TEXH,
        "uv_width": TEXW, "uv_height": TEXH, "particle": True, "use_as_default": False, "layers_enabled": False,
        "sync_to_project": "", "render_mode": "default", "render_sides": "auto", "frame_time": 1,
        "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False, "visible": True,
        "internal": True, "saved": True, "uuid": new_uuid(),
        "source": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode(),
    }],
    "display": DISPLAY,
}
with open(os.path.join(ROOT, "mountain_hammer.bbmodel"), "w", encoding="utf-8") as fp:
    json.dump(bbmodel, fp)

item_model = {
    "credit": "Mountain Giant mod",
    "texture_size": [TEXW, TEXH],
    "textures": {"0": "mountain_giant:item/mountain_hammer", "particle": "mountain_giant:item/mountain_hammer"},
    "elements": [{
        "name": e["name"], "from": e["from"], "to": e["to"],
        "faces": {f: {"uv": [c * 16 / (TEXW if i % 2 == 0 else TEXH) for i, c in enumerate(uv)], "texture": "#0"}
                  for f, uv in e["faces"].items()},
    } for e in elements],
    "display": DISPLAY,
}
# straight into the mod's resources
assets = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "mountain_giant")
os.makedirs(os.path.join(assets, "models", "item"), exist_ok=True)
os.makedirs(os.path.join(assets, "textures", "item"), exist_ok=True)
# 3D hammer in the hands; the flat icon in the inventory, on the ground and in item frames (like vanilla weapons)
with open(os.path.join(assets, "models", "item", "mountain_hammer_3d.json"), "w", encoding="utf-8") as fp:
    json.dump(item_model, fp, indent=1)
flat = {"parent": "minecraft:item/generated", "textures": {"layer0": "mountain_giant:item/mountain_hammer_icon"}}
with open(os.path.join(assets, "models", "item", "mountain_hammer.json"), "w", encoding="utf-8") as fp:
    json.dump({
        "loader": "neoforge:separate_transforms",
        "base": {"parent": "mountain_giant:item/mountain_hammer_3d"},
        "perspectives": {"gui": flat, "ground": flat, "fixed": flat},
    }, fp, indent=1)
with open(os.path.join(assets, "models", "item", "mountain_heart.json"), "w", encoding="utf-8") as fp:
    json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "mountain_giant:item/mountain_heart"}}, fp, indent=1)
tex_img.save(os.path.join(assets, "textures", "item", "mountain_hammer.png"))
heart_img.save(os.path.join(assets, "textures", "item", "mountain_heart.png"))
hammer_icon_img.save(os.path.join(assets, "textures", "item", "mountain_hammer_icon.png"))
hammer_icon_img.save(os.path.join(ROOT, "textures", "mountain_hammer_icon.png"))
print(f"{len(elements)} elements, texture rows used: {y + shelf}/{TEXH}, heart grid {cols}x{rows}")
