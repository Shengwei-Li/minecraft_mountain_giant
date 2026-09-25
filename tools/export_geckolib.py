"""
Export mountain_giant.bbmodel to GeckoLib resources inside the mod
(same conventions as Blockbench's own bedrock/GeckoLib exporter:
 X of positions/pivots mirrored, X/Y rotations negated, up/down face UVs flipped).

python tools/export_geckolib.py
"""
import json
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODID = "mountain_giant"
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", MODID)

model = json.load(open(os.path.join(ROOT, "mountain_giant.bbmodel"), encoding="utf-8"))
elements = {e["uuid"]: e for e in model["elements"]}
res = model["resolution"]


def num(v):
    v = round(float(v), 4)
    return int(v) if v == int(v) else v


def compile_cube(e):
    size = [num(e["to"][i] - e["from"][i]) for i in range(3)]
    origin = [num(e["from"][i]) for i in range(3)]
    origin[0] = num(-(e["from"][0] + size[0]))
    cube = {"origin": origin, "size": size, "uv": {}}
    rot = e.get("rotation", [0, 0, 0])
    if any(rot):
        cube["pivot"] = [num(-e["origin"][0]), num(e["origin"][1]), num(e["origin"][2])]
        cube["rotation"] = [num(-rot[0]), num(-rot[1]), num(rot[2])]
    for key, face in e["faces"].items():
        u1, v1, u2, v2 = face["uv"]
        uv, uv_size = [u1, v1], [u2 - u1, v2 - v1]
        if key in ("up", "down"):
            uv = [uv[0] + uv_size[0], uv[1] + uv_size[1]]
            uv_size = [-uv_size[0], -uv_size[1]]
        cube["uv"][key] = {"uv": [num(x) for x in uv], "uv_size": [num(x) for x in uv_size]}
    return cube


bones = []


def compile_group(g, parent=None):
    bone = {"name": g["name"]}
    if parent:
        bone["parent"] = parent
    bone["pivot"] = [num(-g["origin"][0]), num(g["origin"][1]), num(g["origin"][2])]
    rot = g.get("rotation", [0, 0, 0])
    if any(rot):
        bone["rotation"] = [num(-rot[0]), num(-rot[1]), num(rot[2])]
    cubes = [compile_cube(elements[c]) for c in g["children"] if isinstance(c, str)]
    if cubes:
        bone["cubes"] = cubes
    bones.append(bone)
    for c in g["children"]:
        if isinstance(c, dict):
            compile_group(c, g["name"])


for g in model["outliner"]:
    compile_group(g)

geo = {
    "format_version": "1.12.0",
    "minecraft:geometry": [{
        "description": {
            "identifier": f"geometry.{MODID}",
            "texture_width": res["width"],
            "texture_height": res["height"],
            "visible_bounds_width": 32,
            "visible_bounds_height": 32,
            "visible_bounds_offset": [0, 14, 0],
        },
        "bones": bones,
    }],
}

group_names = {}


def index_groups(g):
    group_names[g["uuid"]] = g["name"]
    for c in g["children"]:
        if isinstance(c, dict):
            index_groups(c)


for g in model["outliner"]:
    index_groups(g)

anims = {}
for a in model["animations"]:
    out = {}
    if a["loop"] == "loop":
        out["loop"] = True
    elif a["loop"] == "hold":
        out["loop"] = "hold_on_last_frame"
    out["animation_length"] = a["length"]
    out_bones = {}
    for uid, animator in a["animators"].items():
        chans = {}
        for k in sorted(animator["keyframes"], key=lambda k: k["time"]):
            dp = k["data_points"][0]
            v = [float(dp["x"]), float(dp["y"]), float(dp["z"])]
            if k["channel"] == "rotation":
                v = [-v[0], -v[1], v[2]]
            elif k["channel"] == "position":
                v = [-v[0], v[1], v[2]]
            t = str(num(k["time"]))
            chans.setdefault(k["channel"], {})[t] = [num(x) for x in v]
        out_bones[group_names.get(uid, animator["name"])] = chans
    out["bones"] = out_bones
    anims[a["name"]] = out

anim_file = {"format_version": "1.8.0", "animations": anims}

paths = {
    "geo": os.path.join(ASSETS, "geo", "entity", f"{MODID}.geo.json"),
    "anim": os.path.join(ASSETS, "animations", "entity", f"{MODID}.animation.json"),
    "tex": os.path.join(ASSETS, "textures", "entity", f"{MODID}.png"),
    "glow": os.path.join(ASSETS, "textures", "entity", f"{MODID}_glowmask.png"),
}
for p in paths.values():
    os.makedirs(os.path.dirname(p), exist_ok=True)
json.dump(geo, open(paths["geo"], "w", encoding="utf-8"), indent=1)
json.dump(anim_file, open(paths["anim"], "w", encoding="utf-8"), indent=1)
shutil.copyfile(os.path.join(ROOT, "textures", "mountain_giant.png"), paths["tex"])
shutil.copyfile(os.path.join(ROOT, "textures", "mountain_giant_glowmask.png"), paths["glow"])
print(f"exported {len(bones)} bones, {len(anims)} animations -> {ASSETS}")
