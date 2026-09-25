"""Writes a 1x1x1 empty structure (gzipped NBT) used as the GameTest template."""
import gzip
import os
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "data", "mountain_giant", "structure", "empty.nbt")


def name(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag_int(n, v):
    return b"\x03" + name(n) + struct.pack(">i", v)


def tag_string(n, v):
    return b"\x08" + name(n) + name(v)


def tag_list_int(n, vals):
    return b"\x09" + name(n) + b"\x03" + struct.pack(">i", len(vals)) + b"".join(struct.pack(">i", v) for v in vals)


def tag_list_compound(n, payloads):
    return b"\x09" + name(n) + b"\x0a" + struct.pack(">i", len(payloads)) + b"".join(p + b"\x00" for p in payloads)


body = (
    tag_int("DataVersion", 3955)  # 1.21.1
    + tag_list_int("size", [1, 1, 1])
    + tag_list_compound("palette", [tag_string("Name", "minecraft:air")])
    + tag_list_compound("blocks", [tag_list_int("pos", [0, 0, 0]) + tag_int("state", 0)])
    + tag_list_compound("entities", [])
)
data = b"\x0a" + name("") + body + b"\x00"
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with gzip.open(OUT, "wb") as f:
    f.write(data)
print("wrote", OUT)
