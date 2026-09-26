"""
Mountain armour + Mountain Plate generator (references/armor.png, references/plate.png).

Builds:
  textures/armor/mountain_layer_1.png   worn helmet, chestplate, boots (vanilla 64x32 armour layout)
  textures/armor/mountain_layer_2.png   worn leggings
  textures/mountain_{helmet,chestplate,leggings,boots}.png   16x16 inventory icons
  textures/mountain_plate.png            texture of the little slab the Mountain Plate item is
  preview/armor_stand.bbmodel            the armour on a player-shaped stand, for tools/preview.py

python tools/gen_armor.py
"""
import base64
import io
import json
import os
import random
import uuid

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rng = np.random.default_rng(11)
rng_uuid = random.Random(5)

PALETTE = {
    "K": (34, 30, 36),                                        # outline
    "S": (170, 166, 168), "s": (146, 142, 145), "d": (116, 112, 117),   # stone light / mid / dark
    "M": (112, 146, 60), "m": (84, 112, 46),                   # moss
    "G": (246, 206, 98), "g": (224, 172, 62), "y": (176, 126, 40),   # gold light / mid / dark
}


def new_uuid():
    return str(uuid.UUID(int=rng_uuid.getrandbits(128), version=4))


# ---------------------------------------------------------------------------
# Worn armour: paint box-UV faces of the vanilla 64x32 humanoid layout
# ---------------------------------------------------------------------------
def box_faces(u0, v0, w, h, d):
    """Vanilla box UV: face -> (x, y, width, height) in the texture."""
    return {
        "up": (u0 + d, v0, w, d), "down": (u0 + d + w, v0, w, d),
        "east": (u0, v0 + d, d, h), "north": (u0 + d, v0 + d, w, h),
        "west": (u0 + d + w, v0 + d, d, h), "south": (u0 + 2 * d + w, v0 + d, w, h),
    }


HEAD = box_faces(0, 0, 8, 8, 8)
BODY = box_faces(16, 16, 8, 12, 4)
ARM = box_faces(40, 16, 4, 12, 4)
LEG = box_faces(0, 16, 4, 12, 4)


def stone_pixel(x, y):
    """Mottled stone with the odd moss patch, the same look as the giant and the hammer."""
    n = rng.random()
    if n < 0.12:
        return "M" if rng.random() < 0.6 else "m"
    if n < 0.35:
        return "S"
    if n < 0.8:
        return "s"
    return "d"


def paint(img, rect, fn):
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            key = fn(x, y, w, h)
            if key is None:
                continue
            base = np.array(PALETTE[key], np.float32)
            if key in "Ssd":
                base += rng.normal(0, 4)
            img[y0 + y, x0 + x] = (*np.clip(base, 0, 255).astype(np.uint8), 255)


def with_moss_top(fn):
    """Moss likes the upper rows."""
    def inner(x, y, w, h):
        k = fn(x, y, w, h)
        if k in ("S", "s", "d") and y < 2 and rng.random() < 0.35:
            return "M"
        return k
    return inner


# ---- layer 1: helmet, chestplate, boots ----
layer1 = np.zeros((32, 64, 4), np.uint8)


def helmet_front(x, y, w, h):
    if y == 0:
        return "g" if 0 < x < w - 1 else "y"                 # brow band
    if 3 <= x <= 4 and y <= 3:
        return "G" if y < 2 else "g"                          # nose guard
    if x <= 1 or x >= w - 2:
        return ("g" if y >= 6 else stone_pixel(x, y))         # cheek guards, gold tips
    if y == 1:
        return stone_pixel(x, y)
    return None                                               # the face stays open


def helmet_side(x, y, w, h):
    if y == 1:
        return "g"                                            # the brow band runs round
    return stone_pixel(x, y)


def helmet_top(x, y, w, h):
    if 3 <= x <= 4:
        return "G" if x == 3 else "g"                         # gold ridge front to back
    return "M" if rng.random() < 0.3 else stone_pixel(x, y)


paint(layer1, HEAD["north"], helmet_front)
for f in ("east", "west", "south"):
    paint(layer1, HEAD[f], helmet_side)
paint(layer1, HEAD["up"], helmet_top)


def chest_front(x, y, w, h):
    if y == h - 1:
        return "g"                                            # bottom trim
    emblem = {(2, 2): "g", (3, 2): "G", (4, 2): "G", (5, 2): "g",
              (3, 3): "g", (4, 3): "G", (3, 4): "y", (4, 4): "g", (3, 5): "y", (4, 5): "y"}
    if (x, y) in emblem:
        return emblem[(x, y)]
    if y == h - 2 and (x <= 1 or x >= w - 2):
        return "g"                                            # side plates
    return stone_pixel(x, y)


def chest_back(x, y, w, h):
    return "g" if y == h - 1 else stone_pixel(x, y)


paint(layer1, BODY["north"], chest_front)
paint(layer1, BODY["south"], chest_back)
paint(layer1, BODY["east"], chest_back)
paint(layer1, BODY["west"], chest_back)
paint(layer1, BODY["up"], with_moss_top(lambda x, y, w, h: stone_pixel(x, y)))


def shoulder(x, y, w, h):
    if y > 5:
        return None                                           # bare below the shoulder pad
    if y == 5:
        return "g"                                            # rim of the pad
    if y == 0:
        return "G" if 0 < x < w - 1 else "g"
    return "M" if (y == 1 and rng.random() < 0.4) else stone_pixel(x, y)


for f in ("east", "north", "west", "south"):
    paint(layer1, ARM[f], shoulder)
paint(layer1, ARM["up"], lambda x, y, w, h: "g" if (x in (0, w - 1) or y in (0, h - 1)) else "M")


def boot_side(front):
    def fn(x, y, w, h):
        if y < h - 5:
            return None
        if y == h - 5:
            return "g"                                        # rim round the top
        if front and y >= h - 2:
            return "G" if y == h - 2 else "g"                 # gold toe cap
        if y == h - 1:
            return "d"
        return stone_pixel(x, y)
    return fn


paint(layer1, LEG["north"], boot_side(True))
for f in ("east", "west", "south"):
    paint(layer1, LEG[f], boot_side(False))
paint(layer1, LEG["down"], lambda x, y, w, h: "d")

# ---- layer 2: leggings ----
layer2 = np.zeros((32, 64, 4), np.uint8)


def belt(front):
    def fn(x, y, w, h):
        if y < h - 5:
            return None                                       # leggings only cover the hips
        if y == h - 5:
            if front and 3 <= x <= 4:
                return "G"                                    # buckle
            return "g"
        if front and y == h - 4 and 3 <= x <= 4:
            return "y"
        return stone_pixel(x, y)
    return fn


paint(layer2, BODY["north"], belt(True))
for f in ("east", "west", "south"):
    paint(layer2, BODY[f], belt(False))


def legging(front):
    def fn(x, y, w, h):
        if y > h - 3:
            return None                                       # the boots take over
        if front and 5 <= y <= 6:
            return "G" if y == 5 else "y"                      # knee plate
        return stone_pixel(x, y)
    return fn


paint(layer2, LEG["north"], legging(True))
for f in ("east", "west", "south"):
    paint(layer2, LEG[f], legging(False))

# ---------------------------------------------------------------------------
# Inventory icons (16x16), pixel by pixel after the concept art
# ---------------------------------------------------------------------------
ICONS = {
    "helmet": [
        "................",
        "................",
        "................",
        "......KKKK......",
        "....KKgGGgKK....",
        "...KsSSgGSSsK...",
        "..KsMMSgGsSMsK..",
        "..KgGGGGGGGGgK..",
        "..KsSKKgyKKdsK..",
        "..KsdK.gy.KdsK..",
        "..KgyK.KK.KgyK..",
        "..KKK......KKK..",
        "................",
        "................",
        "................",
        "................",
    ],
    "chestplate": [
        "................",
        "................",
        "..KKKK....KKKK..",
        ".KgGGgKKKKgGGgK.",
        ".KgMSSsGGsSSMgK.",
        ".KKKsSMgGMSsKKK.",
        "...KSsSygSsSK...",
        "...KsMSSySSsK...",
        "...KSSsMMsSdK...",
        "...KsSSSSsddK...",
        "...KgGGGGGGgK...",
        "...KKKKKKKKKK...",
        "................",
        "................",
        "................",
        "................",
    ],
    "leggings": [
        "................",
        "................",
        "...KKKKKKKKKK...",
        "...KgGGyyGGgK...",
        "...KsSMSSMSsK...",
        "...KSsdKKsSdK...",
        "...KsSK..KMsK...",
        "...KgGK..KgGK...",
        "...KyGK..KyGK...",
        "...KsMK..KSsK...",
        "...KSdK..KsdK...",
        "...KsdK..KsdK...",
        "...KKKK..KKKK...",
        "................",
        "................",
        "................",
    ],
    "boots": [
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "..KKKK....KKKK..",
        "..KgGK....KgGK..",
        "..KsSK....KsSK..",
        "..KMsK....KMsK..",
        "KKsSdK..KKsSdK..",
        "KGGgyK..KGGgyK..",
        "KKKKKK..KKKKKK..",
        "................",
        "................",
        "................",
    ],
}


def icon(rows):
    """Draws the pixel rows, then moves the drawing to the middle of the 16x16 slot."""
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), "icon rows must be 16x16"
    img = np.zeros((16, 16, 4), np.uint8)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                img[y, x] = (*PALETTE[ch], 255)
    ys, xs = np.nonzero(img[:, :, 3])
    dx = (16 - (xs.max() - xs.min() + 1)) // 2 - xs.min()
    dy = (16 - (ys.max() - ys.min() + 1)) // 2 - ys.min()
    return Image.fromarray(np.roll(np.roll(img, dy, axis=0), dx, axis=1), "RGBA")


# ---------------------------------------------------------------------------
# Mountain Plate: a small slab (16 wide, 5 thick). Texture: top 16x16 at (0,0), side 16x5 at (16,0)
# ---------------------------------------------------------------------------
plate = np.zeros((16, 32, 4), np.uint8)


def vein(x, y):
    # two zig-zag gold veins across the top, like the reference
    return (abs(y - (4 + (x // 3) % 2)) == 0 and 7 <= x <= 13) or (abs(x - (6 + (y // 3) % 2)) == 0 and y >= 7)


def plate_top(x, y, w, h):
    if vein(x, y):
        return "G" if (x + y) % 3 else "g"
    if (x < 5 and y > 9) or (x > 11 and y < 4) or (4 <= x <= 7 and 2 <= y <= 4):
        return "M" if rng.random() < 0.75 else "m"
    return stone_pixel(x, y) if rng.random() < 0.85 else "S"


def plate_side(x, y, w, h):
    if x in (3, 11) and y >= 1:
        return "g" if y % 2 else "y"                          # gold running down the edge
    if y == 0 and rng.random() < 0.5:
        return "M"
    return "d" if y == h - 1 else stone_pixel(x, y)


paint(plate, (0, 0, 16, 16), plate_top)
paint(plate, (16, 0, 16, 5), plate_side)

PLATE_MODEL = {
    "credit": "Mountain Giant mod",
    "texture_size": [32, 16],
    "textures": {"0": "mountain_giant:item/mountain_plate", "particle": "mountain_giant:item/mountain_plate"},
    "elements": [{
        "from": [0, 0, 0], "to": [16, 5, 16],
        "faces": {
            "up": {"uv": [0, 0, 8, 16], "texture": "#0"},
            "down": {"uv": [0, 0, 8, 16], "texture": "#0"},
            "north": {"uv": [8, 0, 16, 5], "texture": "#0"},
            "south": {"uv": [8, 0, 16, 5], "texture": "#0"},
            "east": {"uv": [8, 0, 16, 5], "texture": "#0"},
            "west": {"uv": [8, 0, 16, 5], "texture": "#0"},
        },
    }],
    "display": {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 3, 0], "scale": [0.625, 0.625, 0.625]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
        "fixed": {"rotation": [90, 0, 0], "translation": [0, 0, -2], "scale": [0.5, 0.5, 0.5]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
    },
}

# ---------------------------------------------------------------------------
# Write everything
# ---------------------------------------------------------------------------
os.makedirs(os.path.join(ROOT, "textures", "armor"), exist_ok=True)
l1 = Image.fromarray(layer1, "RGBA")
l2 = Image.fromarray(layer2, "RGBA")
l1.save(os.path.join(ROOT, "textures", "armor", "mountain_layer_1.png"))
l2.save(os.path.join(ROOT, "textures", "armor", "mountain_layer_2.png"))
icons = {name: icon(rows) for name, rows in ICONS.items()}
for name, img in icons.items():
    img.save(os.path.join(ROOT, "textures", f"mountain_{name}.png"))
plate_img = Image.fromarray(plate, "RGBA")
plate_img.save(os.path.join(ROOT, "textures", "mountain_plate.png"))
# straight into the mod's resources
assets = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "mountain_giant")
for sub in ("textures/models/armor", "textures/item", "models/item"):
    os.makedirs(os.path.join(assets, sub), exist_ok=True)
l1.save(os.path.join(assets, "textures", "models", "armor", "mountain_layer_1.png"))
l2.save(os.path.join(assets, "textures", "models", "armor", "mountain_layer_2.png"))
for name, img in icons.items():
    img.save(os.path.join(assets, "textures", "item", f"mountain_{name}.png"))
    with open(os.path.join(assets, "models", "item", f"mountain_{name}.json"), "w", encoding="utf-8") as fp:
        json.dump({"parent": "minecraft:item/generated",
                   "textures": {"layer0": f"mountain_giant:item/mountain_{name}"}}, fp, indent=1)
plate_img.save(os.path.join(assets, "textures", "item", "mountain_plate.png"))
with open(os.path.join(assets, "models", "item", "mountain_plate.json"), "w", encoding="utf-8") as fp:
    json.dump(PLATE_MODEL, fp, indent=1)

# ---------------------------------------------------------------------------
# Preview: armour on a player-shaped stand (both layers stacked into one 64x64 texture)
# and the plate slab, as .bbmodel files the preview renderer can draw
# ---------------------------------------------------------------------------
stacked = np.zeros((64, 64, 4), np.uint8)
stacked[:32] = layer1
stacked[32:] = layer2


def cube(name, frm, to, faces_uv, inflate, v_offset=0):
    frm = [frm[i] - inflate for i in range(3)]
    to = [to[i] + inflate for i in range(3)]
    faces = {}
    for f, (x, y, w, h) in faces_uv.items():
        faces[f] = {"uv": [x, y + v_offset, x + w, y + v_offset + h], "texture": 0}
    return {"name": name, "from": frm, "to": to, "origin": [0, 0, 0], "faces": faces, "uuid": new_uuid(),
            "type": "cube", "box_uv": False}


def mirror(faces_uv):
    """The left limb uses the same texture, mirrored: swap east and west."""
    m = dict(faces_uv)
    m["east"], m["west"] = faces_uv["west"], faces_uv["east"]
    return m


stand = [
    cube("helmet", (-4, 24, -4), (4, 32, 4), HEAD, 1.0),
    cube("chest", (-4, 12, -2), (4, 24, 2), BODY, 1.0),
    cube("right_arm", (-8, 12, -2), (-4, 24, 2), ARM, 1.0),
    cube("left_arm", (4, 12, -2), (8, 24, 2), mirror(ARM), 1.0),
    cube("right_boot", (-4, 0, -2), (0, 12, 2), LEG, 1.0),
    cube("left_boot", (0, 0, -2), (4, 12, 2), mirror(LEG), 1.0),
    cube("belt", (-4, 12, -2), (4, 24, 2), BODY, 0.5, 32),
    cube("right_legging", (-4, 0, -2), (0, 12, 2), LEG, 0.5, 32),
    cube("left_legging", (0, 0, -2), (4, 12, 2), mirror(LEG), 0.5, 32),
]


def write_preview_model(path, elements, image, resolution):
    buf = io.BytesIO()
    image.save(buf, "PNG")
    model = {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": os.path.basename(path), "resolution": {"width": resolution[0], "height": resolution[1]},
        "elements": elements, "outliner": [e["uuid"] for e in elements],
        "textures": [{"name": "t.png", "source": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()}],
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(model, fp)


write_preview_model(os.path.join(ROOT, "preview", "armor_stand.bbmodel"), stand,
                    Image.fromarray(stacked, "RGBA"), (64, 64))
slab = [{"name": "plate", "from": [-8, 0, -8], "to": [8, 5, 8], "origin": [0, 2.5, 0], "uuid": new_uuid(),
         "type": "cube", "box_uv": False,
         "faces": {"up": {"uv": [0, 0, 16, 16], "texture": 0}, "down": {"uv": [0, 0, 16, 16], "texture": 0},
                   **{f: {"uv": [16, 0, 32, 5], "texture": 0} for f in ("north", "south", "east", "west")}}}]
write_preview_model(os.path.join(ROOT, "preview", "plate.bbmodel"), slab, plate_img, (32, 16))
print("armour layers, 4 icons, plate texture + model, preview models written")
