"""
Mountain Giant generator.

Builds a GeckoLib (animated_entity_model) .bbmodel with:
  - geometry (bones + cubes), modelled at 1/4 scale: 1 model unit = 1/4 block,
    so the ~113-unit tall model becomes ~28 blocks when rendered at 4x in game
  - a procedurally painted texture (4 texels per unit -> every 4x4-unit patch
    looks like one 16x16 Minecraft block)
  - animations (idle / walk / smash / roar / death)

Run:  python tools/gen_giant.py
Output: mountain_giant.bbmodel, textures/mountain_giant.png, textures/mountain_giant_glowmask.png
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
TPU = 4          # texels per model unit
TILE = 16        # texels per "block"
BLOCK = TILE // TPU  # model units per block (4)

rng_uuid = random.Random(1234)


def new_uuid():
    return str(uuid.UUID(int=rng_uuid.getrandbits(128), version=4))


# ---------------------------------------------------------------------------
# Tiles (16x16 Minecraft-like block textures)
# ---------------------------------------------------------------------------
def _noise(r, base, amp, shape=(TILE, TILE)):
    n = r.normal(0, amp, shape)
    img = np.zeros(shape + (3,), dtype=np.float32)
    for c in range(3):
        img[..., c] = base[c] + n
    return img


def _blobs(r, img, color, count, size_rng=(1, 3), prob=0.7):
    h, w, _ = img.shape
    for _ in range(count):
        cx, cy = r.integers(0, w), r.integers(0, h)
        s = r.integers(size_rng[0], size_rng[1] + 1)
        for dy in range(-s, s + 1):
            for dx in range(-s, s + 1):
                if dx * dx + dy * dy <= s * s and r.random() < prob:
                    img[(cy + dy) % h, (cx + dx) % w] = np.array(color) + r.normal(0, 5, 3)
    return img


def tile_stone(r):
    img = _noise(r, (125, 125, 125), 7)
    _blobs(r, img, (104, 104, 104), 7, (0, 1))
    _blobs(r, img, (145, 145, 145), 5, (0, 1))
    return img


def tile_andesite(r):
    img = _noise(r, (136, 136, 138), 6)
    _blobs(r, img, (112, 112, 114), 6, (1, 2), 0.6)
    _blobs(r, img, (160, 160, 162), 5, (0, 1))
    return img


def tile_cobble(r, base=(118, 118, 118), mortar=(70, 70, 70)):
    # voronoi stones with dark mortar
    pts = [(r.integers(0, TILE), r.integers(0, TILE)) for _ in range(7)]
    shades = [r.uniform(-22, 22) for _ in pts]
    img = np.zeros((TILE, TILE, 3), np.float32)
    for y in range(TILE):
        for x in range(TILE):
            ds = []
            for (px, py) in pts:
                dx = min(abs(x - px), TILE - abs(x - px))
                dy = min(abs(y - py), TILE - abs(y - py))
                ds.append(dx * dx + dy * dy)
            order = np.argsort(ds)
            a, b = ds[order[0]], ds[order[1]]
            if math.sqrt(b) - math.sqrt(a) < 0.9:
                img[y, x] = np.array(mortar) + r.normal(0, 4, 3)
            else:
                s = shades[order[0]]
                img[y, x] = np.array(base) + s + r.normal(0, 6)
    return img


def tile_dark_cobble(r):
    return tile_cobble(r, base=(82, 82, 88), mortar=(44, 44, 48))


def tile_deepslate(r):
    img = _noise(r, (80, 80, 86), 6)
    for y in range(TILE):  # deepslate has horizontal streaks
        if r.random() < 0.35:
            img[y] -= 10
    _blobs(r, img, (100, 100, 106), 4, (0, 1))
    return img


def _moss_over(r, img, amount):
    moss_a = (88, 116, 48)
    moss_b = (112, 146, 62)
    for _ in range(amount):
        _blobs(r, img, moss_a if r.random() < 0.6 else moss_b, 1, (1, 3), 0.75)
    return img


def tile_mossy_cobble(r):
    return _moss_over(r, tile_cobble(r), 6)


def tile_mossy_stone(r):
    return _moss_over(r, tile_stone(r), 5)


def tile_dirt(r):
    img = _noise(r, (134, 96, 67), 8)
    _blobs(r, img, (110, 78, 52), 6, (0, 1))
    _blobs(r, img, (152, 112, 80), 4, (0, 1))
    _blobs(r, img, (120, 120, 120), 2, (0, 0))
    return img


def tile_grass_top(r):
    img = _noise(r, (106, 152, 66), 10)
    _blobs(r, img, (86, 130, 50), 8, (0, 1))
    _blobs(r, img, (128, 172, 84), 6, (0, 0))
    return img


def tile_snow_top(r):
    img = _noise(r, (240, 248, 250), 4)
    _blobs(r, img, (220, 232, 240), 5, (0, 1))
    return img


def _side_cap(r, base_img, top_tile, depth=(2, 5)):
    img = base_img.copy()
    for x in range(TILE):
        d = r.integers(depth[0], depth[1] + 1)
        img[:d, x] = top_tile[:d, x]
    return img


def tile_grass_side(r):
    return _side_cap(r, tile_dirt(r), tile_grass_top(r), (2, 5))


def tile_snow_side(r):
    return _side_cap(r, tile_dirt(r), tile_snow_top(r), (2, 5))


def _ore(r, base_fn, light, dark, clusters=4):
    img = base_fn(r)
    for _ in range(clusters):
        cx, cy = r.integers(2, TILE - 3), r.integers(2, TILE - 3)
        cells = [(cx, cy)]
        for _ in range(r.integers(3, 6)):
            px, py = cells[r.integers(0, len(cells))]
            cells.append((int(np.clip(px + r.integers(-1, 2), 0, TILE - 1)),
                          int(np.clip(py + r.integers(-1, 2), 0, TILE - 1))))
        for (px, py) in cells:
            img[py, px] = np.array(light) + r.normal(0, 6, 3)
        for (px, py) in cells:  # shade bottom-right of cluster
            qx, qy = min(px + 1, TILE - 1), min(py + 1, TILE - 1)
            if (qx, qy) not in cells:
                img[qy, qx] = np.array(dark) + r.normal(0, 4, 3)
    return img


ORES = {
    "ore_iron": ((222, 178, 150), (166, 124, 98)),
    "ore_copper": ((226, 130, 96), (110, 178, 148)),
    "ore_gold": ((252, 236, 80), (196, 150, 30)),
    "ore_coal": ((46, 46, 46), (24, 24, 24)),
    "ore_diamond": ((110, 238, 240), (30, 168, 182)),
}


def tile_vine(r):
    img = _noise(r, (44, 84, 30), 6)
    _blobs(r, img, (66, 120, 40), 7, (0, 1))
    _blobs(r, img, (82, 140, 48), 4, (0, 0))
    _blobs(r, img, (30, 58, 22), 5, (0, 0))
    return img


def tile_diamond_block(r):
    img = _noise(r, (140, 244, 236), 6)
    for i in range(TILE):
        img[i, i] += 30
        img[i, TILE - 1 - i] -= 20
    img[0, :] = (225, 255, 252)
    img[:, 0] = (225, 255, 252)
    img[TILE - 1, :] = (70, 200, 196)
    img[:, TILE - 1] = (70, 200, 196)
    return img


TILE_FNS = {
    "stone": tile_stone,
    "andesite": tile_andesite,
    "cobble": tile_cobble,
    "dark_cobble": tile_dark_cobble,
    "deepslate": tile_deepslate,
    "mossy_cobble": tile_mossy_cobble,
    "mossy_stone": tile_mossy_stone,
    "dirt": tile_dirt,
    "grass": tile_grass_top,
    "grass_side": tile_grass_side,
    "snow": tile_snow_top,
    "snow_side": tile_snow_side,
    "vine": tile_vine,
    "diamond_block": tile_diamond_block,
}
for name, (light, dark) in ORES.items():
    base = tile_deepslate if name == "ore_diamond" else tile_stone
    TILE_FNS[name] = (lambda l, d, b: (lambda r: _ore(r, b, l, d)))(light, dark, base)

VARIANTS = 6
nr = np.random.default_rng(42)
TILES = {k: [np.clip(fn(nr), 0, 255) for _ in range(VARIANTS)] for k, fn in TILE_FNS.items()}

# Material = weighted list of tile types
MATERIALS = {
    "rock": [("stone", 34), ("cobble", 24), ("mossy_cobble", 14), ("andesite", 12),
             ("mossy_stone", 12), ("dirt", 4)],
    "rock_mossy": [("mossy_cobble", 34), ("mossy_stone", 28), ("stone", 14), ("dirt", 12),
                   ("cobble", 12)],
    "rock_dark": [("dark_cobble", 30), ("deepslate", 25), ("stone", 20), ("cobble", 15),
                  ("mossy_cobble", 10)],
    "earth": [("dirt", 55), ("mossy_stone", 20), ("stone", 25)],
    "vine": [("vine", 1)],
}
for name in TILE_FNS:
    MATERIALS.setdefault(name, [(name, 1)])


# ---------------------------------------------------------------------------
# Model definition
# ---------------------------------------------------------------------------
class Bone:
    def __init__(self, name, pivot, rotation=(0, 0, 0), parent=None):
        self.name = name
        self.pivot = list(pivot)
        self.rotation = list(rotation)
        self.parent = parent
        self.children = []   # Bones and Cubes, in order
        self.uuid = new_uuid()
        if parent:
            parent.children.append(self)


class Cube:
    count = 0

    def __init__(self, bone, frm, to, mat="rock", top=None, name=None, rotation=None,
                 origin=None, emissive=False):
        Cube.count += 1
        self.id = Cube.count
        self.bone = bone
        self.frm = [min(a, b) for a, b in zip(frm, to)]
        self.to = [max(a, b) for a, b in zip(frm, to)]
        self.mat = mat
        self.top = top
        self.name = name or f"{bone.name}_c{self.id}"
        self.rotation = rotation
        self.origin = origin or [(a + b) / 2 for a, b in zip(self.frm, self.to)]
        self.emissive = emissive
        self.uuid = new_uuid()
        self.faces = {}
        bone.children.append(self)


def C(bone, frm, size, mat="rock", top=None, **kw):
    """cube from corner + size"""
    to = [frm[i] + size[i] for i in range(3)]
    return Cube(bone, frm, to, mat, top, **kw)


def mirror_x(v):
    return [-v[0], v[1], v[2]]


def C2(bone_r, bone_l, frm, size, mat="rock", top=None, **kw):
    """cube on the right side (+X) and its mirror on the left side"""
    C(bone_r, frm, size, mat, top, **kw)
    lf = [-(frm[0] + size[0]), frm[1], frm[2]]
    kw2 = dict(kw)
    if kw2.get("rotation"):
        r_ = kw2["rotation"]
        kw2["rotation"] = [r_[0], -r_[1], -r_[2]]
    if kw2.get("origin"):
        kw2["origin"] = mirror_x(kw2["origin"])
    C(bone_l, lf, size, mat, top, **kw2)


HUNCH = -32          # torso leans forward (negative X rot = top toward -Z / front)

root = Bone("root", (0, 0, 0))

# ---- legs (short and stocky, set wide apart) ------------------------------
legs = {}
for side, sx in (("right", 1), ("left", -1)):
    leg = Bone(f"{side}_leg", (14 * sx, 34, 4), parent=root)
    shin = Bone(f"{side}_shin", (14 * sx, 20, 4), parent=leg)
    legs[side] = (leg, shin)


def S(side_bones, frm, size, mat="rock", top=None, **kw):
    """helper: same cube on both sides for a (right, left) bone pair"""
    C2(side_bones[0], side_bones[1], frm, size, mat, top, **kw)


L_LEG = (legs["right"][0], legs["left"][0])
L_SHIN = (legs["right"][1], legs["left"][1])

S(L_LEG, (6, 20, -4), (16, 18, 16), "rock")             # thigh
S(L_LEG, (18, 24, -2), (6, 10, 10), "rock_mossy")       # outer thigh chunk
S(L_SHIN, (5, 6, -5), (18, 16, 18), "rock")             # shin
S(L_SHIN, (9, 16, -8), (10, 8, 4), "rock_dark")         # kneecap boulder
S(L_SHIN, (4, 0, -10), (20, 6, 24), "rock_dark")        # foot
S(L_SHIN, (5, 0, -14), (5, 4, 4), "rock_dark")          # toes
S(L_SHIN, (11, 0, -14), (5, 4, 4), "rock_dark")
S(L_SHIN, (17, 0, -13), (6, 4, 3), "rock_dark")
S(L_SHIN, (7, 6, 10), (14, 4, 5), "rock_mossy", top="grass")  # heel ledge

# ---- body -----------------------------------------------------------------
body = Bone("body", (0, 34, 4), parent=root)
C(body, (-20, 30, -8), (40, 14, 24), "rock")                    # pelvis
C(body, (-16, 26, -10), (32, 8, 10), "rock_mossy")              # loin rocks
C(body, (-7, 22, -11), (14, 6, 6), "rock_mossy")

torso = Bone("torso", (0, 42, 4), (HUNCH, 0, 0), parent=body)
C(torso, (-20, 42, -8), (40, 14, 24), "rock")                   # waist
C(torso, (-28, 54, -12), (56, 28, 32), "rock")                  # barrel chest
C(torso, (-20, 58, -16), (40, 20, 4), "rock")                   # chest plates
C(torso, (-24, 74, -4), (48, 18, 28), "rock_mossy", top="grass")  # hump over the back
C(torso, (-18, 56, 20), (36, 16, 4), "rock", top="grass")       # lower back ledge
C(torso, (-30, 76, -10), (60, 6, 20), "rock", top="snow")       # shoulder yoke

# back spikes (like the reference: rock pillars with grass / snow caps)
back = Bone("back_rocks", (0, 92, 10), parent=torso)
C(back, (-4, 92, 2), (10, 22, 10), "rock", top="snow")
C(back, (-16, 92, 8), (8, 16, 8), "rock_mossy", top="grass")
C(back, (9, 92, 10), (8, 14, 8), "rock", top="snow")
C(back, (-22, 90, 16), (8, 10, 7), "rock_mossy", top="grass")
C(back, (14, 90, 17), (8, 11, 7), "rock", top="grass")
C(back, (-6, 92, 15), (9, 9, 8), "rock_mossy", top="grass")
C(back, (0, 114, 5), (4, 6, 4), "rock", top="snow")

# diamond core in the chest
core = Bone("core", (0, 67, -17), parent=torso)
C(core, (-5, 62, -19), (10, 10, 3), "diamond_block", name="diamond_core", emissive=True)

# ---- head (juts forward under the hump) -----------------------------------
head = Bone("head", (0, 82, -10), (22, 0, 0), parent=torso)
C(head, (-12, 72, -34), (24, 22, 22), "rock")                   # skull
C(head, (-13, 88, -37), (26, 5, 6), "rock_mossy", top="grass")  # heavy brow
C(head, (-10, 93, -30), (8, 5, 8), "rock", top="snow")          # head rocks
C(head, (2, 93, -26), (9, 7, 9), "rock_mossy", top="grass")
C(head, (-3, 94, -18), (6, 4, 6), "rock", top="grass")
C(head, (-9, 83, -35), (5, 3, 1), "eye", name="eye_right", emissive=True)
C(head, (4, 83, -35), (5, 3, 1), "eye", name="eye_left", emissive=True)
C(head, (-15, 76, -28), (3, 10, 10), "rock")                    # ears / cheek rocks
C(head, (12, 76, -28), (3, 10, 10), "rock")

nose = Bone("nose", (0, 84, -35), parent=head)
C(nose, (-5, 72, -42), (10, 14, 8), "rock")                     # big nose bridge
C(nose, (-7, 65, -46), (14, 10, 12), "rock")                    # bulbous tip
C(nose, (-4, 64, -47), (8, 3, 4), "rock_mossy")

jaw = Bone("jaw", (0, 74, -14), parent=head)
C(jaw, (-11, 65, -33), (22, 8, 19), "rock_dark")                # jaw
C(jaw, (-9, 73, -33), (3, 3, 2), "stone", name="tusk_r")        # tusks
C(jaw, (6, 73, -33), (3, 3, 2), "stone", name="tusk_l")
beard = Bone("beard", (0, 65, -24), parent=jaw)
for i, (x, ln) in enumerate([(-9, 14), (-5, 20), (-1, 11), (3, 17), (7, 13)]):
    C(beard, (x, 65 - ln, -30 + (i % 2) * 3), (2, ln, 2), "vine", name=f"beard_vine_{i}")

# ---- arms (huge, hanging to the ground, clear of the body) -----------------
ARM_ROT = -HUNCH + 8      # undo the hunch and swing a bit forward
arms = {}
for side, sx in (("right", 1), ("left", -1)):
    arm = Bone(f"{side}_arm", (38 * sx, 80, 4), (ARM_ROT, 0, 12 * sx), parent=torso)
    fore = Bone(f"{side}_forearm", (39 * sx, 52, 5), (-6, 0, -6 * sx), parent=arm)
    hand = Bone(f"{side}_hand", (40 * sx, 18, 5), parent=fore)
    arms[side] = (arm, fore, hand)

L_ARM = (arms["right"][0], arms["left"][0])
L_FORE = (arms["right"][1], arms["left"][1])
L_HAND = (arms["right"][2], arms["left"][2])

S(L_ARM, (26, 70, -8), (24, 22, 26), "rock", top="snow")        # shoulder boulder
S(L_ARM, (33, 92, -2), (8, 8, 8), "rock", top="snow")            # shoulder spikes
S(L_ARM, (43, 90, 8), (6, 12, 6), "rock_mossy", top="grass")
S(L_ARM, (30, 50, -4), (18, 22, 18), "rock")                    # upper arm
S(L_FORE, (27, 18, -8), (26, 34, 26), "rock")                   # huge forearm
S(L_FORE, (30, 44, -11), (14, 8, 4), "rock_mossy", top="grass")
S(L_FORE, (52, 24, -2), (4, 18, 12), "rock")
S(L_HAND, (26, 2, -10), (28, 16, 30), "rock_dark")              # fist
S(L_HAND, (27, 0, -13), (7, 7, 6), "rock_dark")                 # knuckles
S(L_HAND, (35, 0, -13), (7, 7, 6), "rock_dark")
S(L_HAND, (43, 0, -13), (8, 7, 6), "rock_dark")
S(L_HAND, (22, 6, -6), (5, 9, 10), "rock_dark")                 # thumb (inner side)

# ---- vines (each is its own bone so it can sway) ---------------------------
vine_specs = [
    # (parent bone, pivot, [(x, z, length)], name)
    (arms["right"][0], (50, 70, 4), [(51, -5, 18), (51, 4, 28), (49, 13, 14)], "vines_r_shoulder"),
    (arms["left"][0], (-50, 70, 4), [(-51, -5, 24), (-51, 4, 14), (-49, 13, 26)], "vines_l_shoulder"),
    (arms["right"][1], (54, 40, 5), [(57, -2, 16), (57, 9, 24)], "vines_r_forearm"),
    (arms["left"][1], (-54, 40, 5), [(-57, -2, 22), (-57, 9, 12)], "vines_l_forearm"),
    (torso, (0, 74, 25), [(-16, 25, 18), (-6, 26, 28), (5, 25, 16), (15, 25, 24)], "vines_back"),
    (body, (0, 30, -10), [(-13, -11, 14), (-4, -12, 10), (10, -11, 16)], "vines_waist"),
]
for parent, pivot, strands, name in vine_specs:
    # vines hang straight down: cancel the lean of the parent chain
    lean = {"vines_back": -HUNCH, "vines_r_shoulder": -8, "vines_l_shoulder": -8}.get(name, 0)
    vb = Bone(name, pivot, (lean, 0, 0), parent=parent)
    for i, (x, z, ln) in enumerate(strands):
        C(vb, (x - 1, pivot[1] - ln, z - 1), (2, ln, 2), "vine", name=f"{name}_{i}")
        C(vb, (x - 2, pivot[1] - ln, z - 2), (4, 3, 4), "vine", name=f"{name}_{i}_leaf")

# ---- ores: every ore cluster is its own bone so it can be hidden when mined ----
ore_specs = [
    ("ore_iron_r_shoulder", arms["right"][0], "ore_iron", (50, 76, 0), (2, 8, 8)),
    ("ore_iron_l_shoulder", arms["left"][0], "ore_iron", (-52, 74, 4), (2, 8, 8)),
    ("ore_copper_r_forearm", arms["right"][1], "ore_copper", (34, 28, -10), (8, 8, 2)),
    ("ore_copper_l_forearm", arms["left"][1], "ore_copper", (-44, 30, -10), (8, 8, 2)),
    ("ore_gold_r_shin", legs["right"][1], "ore_gold", (10, 8, -7), (8, 8, 2)),
    ("ore_gold_l_thigh", legs["left"][0], "ore_gold", (-18, 24, -6), (8, 8, 2)),
    ("ore_coal_l_shin", legs["left"][1], "ore_coal", (-25, 8, 0), (2, 8, 8)),
    ("ore_coal_chest", torso, "ore_coal", (10, 68, -18), (8, 8, 2)),
    ("ore_iron_back", torso, "ore_iron", (-14, 58, 24), (8, 8, 2)),
    ("ore_copper_hump", torso, "ore_copper", (8, 80, 24), (8, 8, 2)),
]
for name, parent, mat, frm, size in ore_specs:
    ob = Bone(name, [frm[i] + size[i] / 2 for i in range(3)], parent=parent)
    C(ob, frm, size, mat, name=name)


# ---------------------------------------------------------------------------
# UV layout + texture painting
# ---------------------------------------------------------------------------
def all_cubes(b):
    for ch in b.children:
        if isinstance(ch, Cube):
            yield ch
        else:
            yield from all_cubes(ch)


def all_bones(b):
    yield b
    for ch in b.children:
        if isinstance(ch, Bone):
            yield from all_bones(ch)


cubes = list(all_cubes(root))
face_list = []
for c in cubes:
    dx, dy, dz = (c.to[i] - c.frm[i] for i in range(3))
    dims = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}
    for f, (w, h) in dims.items():
        face_list.append([c, f, int(math.ceil(w)), int(math.ceil(h))])

UV_W = 512
face_list.sort(key=lambda t: (-t[3], -t[2]))
x = y = shelf_h = 0
for fe in face_list:
    w, h = fe[2], fe[3]
    if x + w > UV_W:
        x, y = 0, y + shelf_h
        shelf_h = 0
    fe.append(x)
    fe.append(y)
    x += w
    shelf_h = max(shelf_h, h)
UV_H = y + shelf_h
UV_H = int(math.ceil(UV_H / 16) * 16)

tex = np.zeros((UV_H * TPU, UV_W * TPU, 4), np.float32)


def pick(r, mat):
    items = MATERIALS[mat]
    tot = sum(wt for _, wt in items)
    v = r.random() * tot
    for name, wt in items:
        v -= wt
        if v <= 0:
            return name
    return items[-1][0]


def paint_eye(w, h):
    img = np.zeros((h, w, 3), np.float32)
    for yy in range(h):
        for xx in range(w):
            ex = abs((xx + 0.5) / w - 0.5) * 2
            ey = abs((yy + 0.5) / h - 0.5) * 2
            d = max(ex, ey)
            img[yy, xx] = (255, 255, 225) if d < 0.5 else (255, 238, 140) if d < 0.8 else (255, 190, 70)
    return img


FACE_IDX = {"north": 0, "south": 1, "east": 2, "west": 3, "up": 4, "down": 5}
for c, f, w, h, u, v in face_list:
    c.faces[f] = [u, v, u + w, v + h]
    pw, ph = w * TPU, h * TPU
    r = random.Random(c.id * 131 + FACE_IDX[f] * 17)
    if c.mat == "eye":
        img = paint_eye(pw, ph)
    else:
        img = np.zeros((ph, pw, 3), np.float32)
        dominant = pick(r, c.mat)   # keep each face coherent, like one rock slab
        for j in range(0, ph, TILE):
            for i in range(0, pw, TILE):
                side = f in ("north", "south", "east", "west")
                if f == "up" and c.top:
                    t = c.top
                elif side and c.top and j == 0:
                    t = c.top + "_side"
                else:
                    t = dominant if r.random() < 0.6 else pick(r, c.mat)
                tile = TILES[t][r.randrange(VARIANTS)]
                if not t.endswith("_side"):
                    k = r.randrange(4)
                    tile = np.rot90(tile, k)
                    if r.random() < 0.5:
                        tile = tile[:, ::-1]
                tile = tile * r.uniform(0.92, 1.06)
                hh, ww = min(TILE, ph - j), min(TILE, pw - i)
                img[j:j + hh, i:i + ww] = tile[:hh, :ww]
        if f == "down":
            img *= 0.82   # undersides a little darker
    tex[v * TPU:(v + h) * TPU, u * TPU:(u + w) * TPU, :3] = img
    tex[v * TPU:(v + h) * TPU, u * TPU:(u + w) * TPU, 3] = 255

tex_img = Image.fromarray(np.clip(tex, 0, 255).astype(np.uint8), "RGBA")
os.makedirs(os.path.join(ROOT, "textures"), exist_ok=True)
tex_path = os.path.join(ROOT, "textures", "mountain_giant.png")
tex_img.save(tex_path)

# glow mask (GeckoLib auto-glowing layer): only eyes + core
glow = np.zeros_like(tex)
for c, f, w, h, u, v in face_list:
    if c.emissive:
        glow[v * TPU:(v + h) * TPU, u * TPU:(u + w) * TPU] = tex[v * TPU:(v + h) * TPU, u * TPU:(u + w) * TPU]
Image.fromarray(np.clip(glow, 0, 255).astype(np.uint8), "RGBA").save(
    os.path.join(ROOT, "textures", "mountain_giant_glowmask.png"))


# ---------------------------------------------------------------------------
# Animations
# ---------------------------------------------------------------------------
BONES = {b.name: b for b in all_bones(root)}


class Anim:
    def __init__(self, name, length, loop="loop"):
        self.name = f"animation.mountain_giant.{name}"
        self.length = length
        self.loop = loop
        self.tracks = {}  # bone -> channel -> [(t, (x,y,z))]

    def key(self, bone, channel, t, xyz):
        self.tracks.setdefault(bone, {}).setdefault(channel, []).append((round(t, 4), xyz))

    def curve(self, bone, channel, fn, steps=12):
        """sample fn(phase in [0,1]) -> (x,y,z) over the whole animation"""
        for i in range(steps + 1):
            p = i / steps
            self.key(bone, channel, p * self.length, tuple(round(v, 3) for v in fn(p)))


TAU = 2 * math.pi
sin = lambda p, ph=0.0: math.sin(TAU * (p + ph))
cos = lambda p, ph=0.0: math.cos(TAU * (p + ph))
VINES = [n for n in BONES if n.startswith("vines_")] + ["beard"]

anims = []

# idle: slow breathing, swaying vines
a = Anim("idle", 4.0)
a.curve("torso", "rotation", lambda p: (1.5 * sin(p), 0, 0))
a.curve("head", "rotation", lambda p: (-2 * sin(p, 0.1), 3 * sin(p, 0.3) * 0.5, 0))
a.curve("jaw", "rotation", lambda p: (-2 - 2 * sin(p, 0.15), 0, 0))
a.curve("right_arm", "rotation", lambda p: (1.5 * sin(p, 0.2), 0, 1.5 * sin(p)))
a.curve("left_arm", "rotation", lambda p: (1.5 * sin(p, 0.2), 0, -1.5 * sin(p)))
a.curve("body", "position", lambda p: (0, 0.4 * sin(p), 0))
for i, vn in enumerate(VINES):
    a.curve(vn, "rotation", lambda p, i=i: (3 * sin(p, i * 0.17), 0, 4 * sin(p, i * 0.23 + 0.1)))
anims.append(a)

# walk: slow heavy gait (legs +-16 deg -> ~4.7 blocks per step). Blockbench: +X rot swings a hanging limb forward (-Z)
a = Anim("walk", 3.2)
a.curve("right_leg", "rotation", lambda p: (16 * sin(p), 0, 0), 16)
a.curve("left_leg", "rotation", lambda p: (-16 * sin(p), 0, 0), 16)
a.curve("right_shin", "rotation", lambda p: (-22 * max(0.0, cos(p)), 0, 0), 16)
a.curve("left_shin", "rotation", lambda p: (-22 * max(0.0, -cos(p)), 0, 0), 16)
a.curve("right_arm", "rotation", lambda p: (-11 * sin(p), 0, 2 * sin(p)), 16)
a.curve("left_arm", "rotation", lambda p: (11 * sin(p), 0, 2 * sin(p)), 16)
a.curve("right_forearm", "rotation", lambda p: (-8 * max(0.0, sin(p, 0.5)), 0, 0), 16)
a.curve("left_forearm", "rotation", lambda p: (-8 * max(0.0, sin(p)), 0, 0), 16)
a.curve("body", "rotation", lambda p: (0, 6 * sin(p), 3 * sin(p)), 16)
a.curve("body", "position", lambda p: (0, -1.8 * abs(cos(p)) + 0.9, 0), 16)
a.curve("torso", "rotation", lambda p: (1.5 * cos(2 * p), -3 * sin(p), -2 * sin(p)), 16)
a.curve("head", "rotation", lambda p: (2 * cos(2 * p), 3 * sin(p), 2 * sin(p)), 16)
for i, vn in enumerate(VINES):
    a.curve(vn, "rotation", lambda p, i=i: (6 * sin(2 * p, 0.2 + i * 0.1), 0, 6 * sin(p, 0.25 + i * 0.13)), 16)
anims.append(a)

# smash: both fists up and slam the ground in front (used when flattening / attacking)
a = Anim("smash", 2.4, "false")
for bn, keys in {
    "right_arm": [(0, (0, 0, 0)), (0.9, (150, 0, 10)), (1.2, (150, 0, 10)), (1.4, (55, 0, 0)), (1.9, (55, 0, 0)), (2.4, (0, 0, 0))],
    "left_arm": [(0, (0, 0, 0)), (0.9, (150, 0, -10)), (1.2, (150, 0, -10)), (1.4, (55, 0, 0)), (1.9, (55, 0, 0)), (2.4, (0, 0, 0))],
    "right_forearm": [(0, (0, 0, 0)), (0.9, (40, 0, 0)), (1.2, (40, 0, 0)), (1.4, (0, 0, 0)), (2.4, (0, 0, 0))],
    "left_forearm": [(0, (0, 0, 0)), (0.9, (40, 0, 0)), (1.2, (40, 0, 0)), (1.4, (0, 0, 0)), (2.4, (0, 0, 0))],
    "torso": [(0, (0, 0, 0)), (0.9, (14, 0, 0)), (1.2, (16, 0, 0)), (1.4, (-16, 0, 0)), (1.9, (-16, 0, 0)), (2.4, (0, 0, 0))],
    "head": [(0, (0, 0, 0)), (0.9, (10, 0, 0)), (1.4, (-4, 0, 0)), (2.4, (0, 0, 0))],
    "jaw": [(0, (0, 0, 0)), (0.9, (-18, 0, 0)), (1.4, (-6, 0, 0)), (2.4, (0, 0, 0))],
    "body": [(0, (0, 0, 0)), (1.2, (0, 0, 0)), (1.4, (0, 0, 0)), (2.4, (0, 0, 0))],
    "right_shin": [(0, (0, 0, 0)), (1.2, (0, 0, 0)), (1.4, (-14, 0, 0)), (1.9, (-14, 0, 0)), (2.4, (0, 0, 0))],
    "left_shin": [(0, (0, 0, 0)), (1.2, (0, 0, 0)), (1.4, (-14, 0, 0)), (1.9, (-14, 0, 0)), (2.4, (0, 0, 0))],
    "right_leg": [(0, (0, 0, 0)), (1.2, (0, 0, 0)), (1.4, (12, 0, 0)), (1.9, (12, 0, 0)), (2.4, (0, 0, 0))],
    "left_leg": [(0, (0, 0, 0)), (1.2, (0, 0, 0)), (1.4, (12, 0, 0)), (1.9, (12, 0, 0)), (2.4, (0, 0, 0))],
}.items():
    for t, xyz in keys:
        a.key(bn, "rotation", t, xyz)
for t, y in [(0, 0), (1.2, 1), (1.4, -5), (1.9, -5), (2.4, 0)]:
    a.key("body", "position", t, (0, y, 0))
anims.append(a)

# roar: head up, jaw wide, arms spread
a = Anim("roar", 2.8, "false")
for bn, keys in {
    "head": [(0, (0, 0, 0)), (0.6, (28, 0, 0)), (2.2, (26, 0, 0)), (2.8, (0, 0, 0))],
    "jaw": [(0, (0, 0, 0)), (0.6, (-32, 0, 0)), (2.2, (-30, 0, 0)), (2.8, (0, 0, 0))],
    "torso": [(0, (0, 0, 0)), (0.6, (12, 0, 0)), (2.2, (12, 0, 0)), (2.8, (0, 0, 0))],
    "right_arm": [(0, (0, 0, 0)), (0.6, (10, 0, -35)), (2.2, (10, 0, -35)), (2.8, (0, 0, 0))],
    "left_arm": [(0, (0, 0, 0)), (0.6, (10, 0, 35)), (2.2, (10, 0, 35)), (2.8, (0, 0, 0))],
    "right_forearm": [(0, (0, 0, 0)), (0.6, (35, 0, 0)), (2.2, (35, 0, 0)), (2.8, (0, 0, 0))],
    "left_forearm": [(0, (0, 0, 0)), (0.6, (35, 0, 0)), (2.2, (35, 0, 0)), (2.8, (0, 0, 0))],
}.items():
    for t, xyz in keys:
        a.key(bn, "rotation", t, xyz)
# shake during the roar
for i in range(1, 12):
    a.key("head", "rotation", 0.6 + i * 0.13, (27 + (1.5 if i % 2 else -1.5), (2 if i % 2 else -2), 0))
anims.append(a)

# death: drop to the knees, then fall forward and stay down
a = Anim("death", 3.2, "hold")
for bn, keys in {
    "right_leg": [(0, (0, 0, 0)), (0.8, (60, 0, 0)), (3.2, (60, 0, 0))],
    "left_leg": [(0, (0, 0, 0)), (0.8, (60, 0, 0)), (3.2, (60, 0, 0))],
    "right_shin": [(0, (0, 0, 0)), (0.8, (-90, 0, 0)), (3.2, (-90, 0, 0))],
    "left_shin": [(0, (0, 0, 0)), (0.8, (-90, 0, 0)), (3.2, (-90, 0, 0))],
    "body": [(0, (0, 0, 0)), (0.8, (8, 0, 0)), (1.3, (6, 0, 0)), (2.4, (-66, 0, 0)), (2.6, (-60, 0, 0)), (3.2, (-62, 0, 0))],
    "head": [(0, (0, 0, 0)), (0.8, (20, 0, 0)), (2.4, (-10, 0, 8)), (3.2, (-10, 0, 8))],
    "jaw": [(0, (0, 0, 0)), (0.8, (-25, 0, 0)), (2.4, (-12, 0, 0)), (3.2, (-12, 0, 0))],
    "right_arm": [(0, (0, 0, 0)), (0.8, (-10, 0, -15)), (2.4, (90, 0, -20)), (3.2, (90, 0, -20))],
    "left_arm": [(0, (0, 0, 0)), (0.8, (-10, 0, 15)), (2.4, (90, 0, 20)), (3.2, (90, 0, 20))],
}.items():
    for t, xyz in keys:
        a.key(bn, "rotation", t, xyz)
for t, y in [(0, 0), (0.8, -17), (2.4, -17), (3.2, -17)]:
    a.key("body", "position", t, (0, y, 0))
anims.append(a)


# ---------------------------------------------------------------------------
# Write .bbmodel (GeckoLib animated_entity_model, Blockbench 4.10 layout;
# Blockbench 5 upgrades it on open)
# ---------------------------------------------------------------------------
def element_json(c):
    faces = {}
    for f, uv in c.faces.items():
        faces[f] = {"uv": uv, "texture": 0}
    e = {
        "name": c.name,
        "box_uv": False,
        "rescale": False,
        "locked": False,
        "light_emission": 15 if c.emissive else 0,
        "render_order": "default",
        "allow_mirror_modeling": True,
        "from": c.frm,
        "to": c.to,
        "autouv": 0,
        "color": 0,
        "origin": c.origin,
        "faces": faces,
        "type": "cube",
        "uuid": c.uuid,
    }
    if c.rotation:
        e["rotation"] = c.rotation
    return e


COLORS = {"leg": 1, "shin": 1, "arm": 2, "forearm": 2, "hand": 2, "head": 3, "jaw": 3,
          "nose": 3, "vines": 4, "beard": 4, "ore": 5, "core": 6}


def group_json(b):
    color = 0
    for k, v in COLORS.items():
        if k in b.name:
            color = v
    g = {
        "name": b.name,
        "origin": b.pivot,
        "color": color,
        "uuid": b.uuid,
        "export": True,
        "mirror_uv": False,
        "isOpen": b.name in ("root", "body", "torso"),
        "locked": False,
        "visibility": True,
        "autouv": 0,
        "children": [],
    }
    if any(b.rotation):
        g["rotation"] = b.rotation
    for ch in b.children:
        g["children"].append(group_json(ch) if isinstance(ch, Bone) else ch.uuid)
    return g


def anim_json(a):
    animators = {}
    for bn, chans in a.tracks.items():
        b = BONES[bn]
        kfs = []
        for ch, keys in chans.items():
            seen = {}
            for t, xyz in keys:
                seen[t] = xyz
            for t, xyz in sorted(seen.items()):
                kfs.append({
                    "channel": ch,
                    "data_points": [{"x": xyz[0], "y": xyz[1], "z": xyz[2]}],
                    "uuid": new_uuid(),
                    "time": t,
                    "color": -1,
                    "interpolation": "linear",
                })
        animators[b.uuid] = {"name": bn, "type": "bone", "keyframes": kfs}
    return {
        "uuid": new_uuid(),
        "name": a.name,
        "loop": a.loop,
        "override": False,
        "length": a.length,
        "snapping": 20,
        "selected": False,
        "anim_time_update": "",
        "blend_weight": "",
        "start_delay": "",
        "loop_delay": "",
        "animators": animators,
    }


buf = io.BytesIO()
tex_img.save(buf, "PNG")
tex_b64 = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()

model = {
    "meta": {"format_version": "4.10", "model_format": "animated_entity_model", "box_uv": False},
    "name": "mountain_giant",
    "model_identifier": "mountain_giant",
    "visible_box": [14, 28, 7],
    "variable_placeholders": "",
    "variable_placeholder_buttons": [],
    "timeline_setups": [],
    "unhandled_root_fields": {},
    "resolution": {"width": UV_W, "height": UV_H},
    "elements": [element_json(c) for c in cubes],
    "outliner": [group_json(root)],
    "textures": [{
        "path": tex_path,
        "name": "mountain_giant.png",
        "folder": "",
        "namespace": "",
        "id": "0",
        "width": UV_W * TPU,
        "height": UV_H * TPU,
        "uv_width": UV_W,
        "uv_height": UV_H,
        "particle": False,
        "use_as_default": False,
        "layers_enabled": False,
        "sync_to_project": "",
        "render_mode": "default",
        "render_sides": "auto",
        "frame_time": 1,
        "frame_order_type": "loop",
        "frame_order": "",
        "frame_interpolate": False,
        "visible": True,
        "internal": True,
        "saved": True,
        "uuid": new_uuid(),
        "source": tex_b64,
    }],
    "animations": [anim_json(a) for a in anims],
}

out = os.path.join(ROOT, "mountain_giant.bbmodel")
with open(out, "w", encoding="utf-8") as fp:
    json.dump(model, fp)

print(f"cubes: {len(cubes)}  bones: {len(BONES)}  uv: {UV_W}x{UV_H}  texture: {UV_W*TPU}x{UV_H*TPU}")
print("wrote", out)
