"""
Poster (1920x1080) and banner (1920x480) from two in-game screenshots in the project root
(day2.png, night.png): day on the left, night on the right, split by a glowing crack; the title in big
block letters; the horn, the hammer and the armour rendered from their models.

python tools/make_poster.py  ->  docs/images/poster.png, docs/images/banner.png
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pixelfont import text_mask  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREVIEW_BG = np.array([200, 220, 240])
DAY = ("day2.png", (1065, 520))      # the screenshot and the point to centre on (the giant)
NIGHT = ("night.png", (1200, 490))   # (the glowing eyes)


def arr(img):
    return np.asarray(img).astype(np.float32)


def to_img(a, mode="RGB"):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), mode)


def place(path, focus, target, w, h, zoom=1.0):
    """Scale an image to cover w x h (times zoom) and put its focus pixel as close to target as it can
    while still covering everything."""
    img = Image.open(os.path.join(ROOT, path)).convert("RGB")
    s = max(w / img.width, h / img.height) * zoom
    img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
    x0 = int(np.clip(focus[0] * s - target[0], 0, img.width - w))
    y0 = int(np.clip(focus[1] * s - target[1], 0, img.height - h))
    return arr(img.crop((x0, y0, x0 + w, y0 + h)))


def saturate(a, k):
    grey = a.mean(axis=2, keepdims=True)
    return grey + (a - grey) * k


# ---------------------------------------------------------------------------
# Block-letter text
# ---------------------------------------------------------------------------
def block_text(text, block, top, bottom, depth=0, outline=6, bevel=True, edge=(22, 12, 6)):
    mask = text_mask(text)
    px = np.kron(mask, np.ones((block, block), bool))
    pad = outline + depth + 4
    h, w = px.shape[0] + 2 * pad, px.shape[1] + 2 * pad
    face = np.zeros((h, w), bool)
    face[pad:pad + px.shape[0], pad:pad + px.shape[1]] = px
    rgba = np.zeros((h, w, 4), np.float32)
    # extrusion down and to the right
    ext = np.zeros_like(face)
    for k in range(1, depth + 1):
        ext |= np.roll(face, (k, k // 2), axis=(0, 1))
    body = face | ext
    ol = np.asarray(Image.fromarray(body.astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(outline * 2 + 1))) > 0
    rgba[ol] = [*edge, 255]
    rgba[ext & ~face] = [*(np.array(bottom, np.float32) * 0.45), 255]
    # the face: a gradient from top to bottom, each font pixel bevelled like a block
    fy = np.clip((np.arange(h) - pad) / max(1, px.shape[0] - 1), 0, 1)
    top_c, bottom_c = np.array(top, np.float32), np.array(bottom, np.float32)
    grad = top_c[None] + (bottom_c - top_c)[None] * fy[:, None]
    ys, xs = np.nonzero(face)
    rgba[ys, xs, :3] = grad[ys]
    rgba[ys, xs, 3] = 255
    if bevel:
        b = max(2, block // 6)
        ly, lx = (ys - pad) % block, (xs - pad) % block
        hi = (ly < b) | (lx < b)
        lo = ((ly >= block - b) | (lx >= block - b)) & ~hi
        rgba[ys[hi], xs[hi], :3] = np.minimum(255, rgba[ys[hi], xs[hi], :3] * 1.18 + 20)
        rgba[ys[lo], xs[lo], :3] *= 0.78
    return to_img(rgba, "RGBA")


def paste_center(base, img, cx, cy, shadow=True):
    x, y = int(cx - img.width / 2), int(cy - img.height / 2)
    if shadow:
        sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
        sh.putalpha(img.getchannel("A").point(lambda a: a * 0.7))
        base.alpha_composite(sh.filter(ImageFilter.GaussianBlur(14)), (x + 10, y + 14))
    base.alpha_composite(img, (x, y))


def label(text, block=5):
    return block_text(text, block, (255, 255, 255), (255, 226, 150), outline=3, bevel=False)


# ---------------------------------------------------------------------------
# Items rendered from their models, with a coloured glow
# ---------------------------------------------------------------------------
def cutout(model, span, center, yaw, pitch, pivot="0,0"):
    subprocess.run([sys.executable, os.path.join(ROOT, "tools", "preview.py"), "--model", model, "--span", str(span),
                    "--center", str(center), "--pivot", pivot, "--out", "poster_cut", "view", str(yaw), str(pitch)],
                   check=True, stdout=subprocess.DEVNULL)
    a = arr(Image.open(os.path.join(ROOT, "preview", "poster_cut.png")).convert("RGB"))
    alpha = (np.abs(a - PREVIEW_BG).max(axis=2) > 1) * 255.0
    img = to_img(np.dstack([a, alpha]), "RGBA")
    return img.crop(img.getbbox())


def glowing(img, height, color, angle=0):
    img = img.resize((round(img.width * height / img.height), height), Image.NEAREST)
    if angle:
        img = img.rotate(angle, resample=Image.NEAREST, expand=True)
    pad = max(20, height // 6)
    out = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
    halo = Image.new("RGBA", out.size, (*color, 0))
    a = Image.new("L", out.size, 0)
    a.paste(img.getchannel("A"), (pad, pad))
    edge = a.filter(ImageFilter.MaxFilter(9 if height > 200 else 5))
    halo.putalpha(edge.filter(ImageFilter.GaussianBlur(max(6, height // 24))))
    out = Image.alpha_composite(out, halo)
    out = Image.alpha_composite(out, halo)
    rim = Image.new("RGBA", out.size, (255, 255, 255, 0))
    rim.putalpha(edge.point(lambda v: 255 if v else 0))
    out = Image.alpha_composite(out, rim)
    out.alpha_composite(img, (pad, pad))
    return out


ITEMS = {
    "horn": (cutout("mountain_horn.bbmodel", 26, 8, 200, -20, "8,8"), (120, 255, 150), 0),
    "hammer": (cutout("mountain_hammer.bbmodel", 48, 9, 210, -15, "8,8"), (255, 190, 60), -18),
    "armor": (cutout(os.path.join("preview", "armor_stand.bbmodel"), 40, 16, 200, -10), (110, 220, 255), 0),
}


def item(name, height):
    img, color, angle = ITEMS[name]
    return glowing(img, height, color, angle)


# ---------------------------------------------------------------------------
# Background: day | glowing crack | night
# ---------------------------------------------------------------------------
def background(w, h, day_at, night_at, crack_top, crack_bottom, zoom_day=1.0, zoom_night=1.12):
    # each photo only has to fill its own side of the crack (plus a little overlap)
    dw = int(max(crack_top, crack_bottom) + 60)
    day = np.zeros((h, w, 3), np.float32)
    day[:, :dw] = place(DAY[0], DAY[1], day_at, dw, h, zoom_day)
    day = saturate(day, 1.25) * np.array([1.04, 1.0, 0.95])               # warmer, punchier

    nx = int(min(crack_top, crack_bottom) - 60)
    night = np.zeros((h, w, 3), np.float32)
    night[:, nx:] = place(NIGHT[0], NIGHT[1], (night_at[0] - nx, night_at[1]), w - nx, h, zoom_night)
    eyes = (night[..., 0] > 195) & (night[..., 1] > 170) & (night.sum(axis=2) > 540)
    night = 255 * (night / 255) ** 0.75 * 1.08                             # lift it out of the dark
    night = night * np.array([0.9, 0.95, 1.12]) + np.array([2, 3, 10])     # moonlit blue
    glow = np.zeros_like(night)
    glow[eyes] = [255, 200, 80]
    glow_img = to_img(glow).filter(ImageFilter.MaxFilter(21))  # a small bright spot would vanish when blurred
    for r, k in ((8, 0.8), (24, 1.0), (64, 1.6)):
        night += arr(glow_img.filter(ImageFilter.GaussianBlur(r))) * k
    night[eyes] = [255, 240, 170]

    steps = max(6, h // 90)
    pts = []
    for i in range(steps + 1):
        y = i * h / steps
        pts.append((crack_top + (crack_bottom - crack_top) * y / h + (24 if i % 2 else -24), y))
    split = Image.new("L", (w, h), 0)
    ImageDraw.Draw(split).polygon(pts + [(w, h), (w, 0)], fill=255)
    m = arr(split)[..., None] / 255
    bg = day * (1 - m) + night * m

    yy, xx = np.mgrid[0:h, 0:w]
    vign = 1 - 0.45 * (((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2) ** 1.5
    bg *= np.clip(vign, 0.35, 1)[..., None]
    canvas = to_img(bg).convert("RGBA")

    crack = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(crack).line(pts, fill=(120, 240, 255, 255), width=10, joint="curve")
    glow = crack.filter(ImageFilter.GaussianBlur(14))
    canvas = Image.alpha_composite(canvas, glow)
    canvas = Image.alpha_composite(canvas, glow)
    core = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(core).line(pts, fill=(235, 255, 255, 255), width=4, joint="curve")
    return Image.alpha_composite(canvas, core)


def darken(canvas, top_frac=0.0, bottom_frac=0.0, top_k=0.45, bottom_k=0.55):
    a = arr(canvas.convert("RGB"))
    h = a.shape[0]
    yy = np.arange(h)[:, None, None]
    if top_frac:
        a *= 1 - top_k * np.clip(1 - yy / (h * top_frac), 0, 1) ** 1.6
    if bottom_frac:
        a *= 1 - bottom_k * np.clip((yy - h * (1 - bottom_frac)) / (h * bottom_frac), 0, 1) ** 1.4
    return to_img(a).convert("RGBA")


SUBTITLE = "A 28 BLOCK TALL BOSS WALKS THE PLAINS"


def subtitle(block):
    return block_text(SUBTITLE, block, (255, 255, 255), (206, 226, 255), depth=max(2, block // 2), outline=4,
                      bevel=False, edge=(10, 14, 30))


def tag(block=5):
    return block_text("NEOFORGE 1.21.1", block, (255, 255, 255), (200, 220, 255), outline=3, bevel=False,
                      edge=(10, 14, 30))


# ---------------------------------------------------------------------------
# Poster 1920x1080
# ---------------------------------------------------------------------------
W, H = 1920, 1080
poster = background(W, H, (470, 450), (1440, 470), 1010, 840)
poster = darken(poster, top_frac=0.33, bottom_frac=0.38)
paste_center(poster, block_text("MOUNTAIN GIANT", 21, (255, 240, 120), (246, 128, 22), depth=12, outline=7), W / 2, 150)
paste_center(poster, subtitle(7), W / 2, 290, shadow=False)
for name, x, y, size, text in (("horn", 1080, 930, 190, "HORN"), ("hammer", 1420, 830, 380, "HAMMER"),
                               ("armor", 1740, 840, 360, "ARMOR")):
    paste_center(poster, item(name, size), x, y)
    paste_center(poster, label(text), x, 1040, shadow=False)
t = tag()
paste_center(poster, t, 60 + t.width / 2, H - 50, shadow=False)

# ---------------------------------------------------------------------------
# Banner 1920x480: the whole day giant on the left, the night eyes on the right, title in a dark middle panel
# ---------------------------------------------------------------------------
BW, BH = 1920, 480


def crack_line(x_top, x_bottom, h, steps=6, amp=16):
    return [(x_top + (x_bottom - x_top) * i / steps + (amp if i % 2 else -amp), i * h / steps) for i in range(steps + 1)]


left = crack_line(700, 640, BH)
right = crack_line(1280, 1220, BH)
day = saturate(place(DAY[0], DAY[1], (400, 240), 760, BH), 1.25) * np.array([1.04, 1.0, 0.95])
night = place(NIGHT[0], NIGHT[1], (1560 - 1160, 190), BW - 1160, BH)
eyes = (night[..., 0] > 195) & (night[..., 1] > 170) & (night.sum(axis=2) > 540)
night = 255 * (night / 255) ** 0.75 * 1.08
night = night * np.array([0.9, 0.95, 1.12]) + np.array([2, 3, 10])
g = np.zeros_like(night)
g[eyes] = [255, 200, 80]
g_img = to_img(g).filter(ImageFilter.MaxFilter(13))
for r, k in ((6, 0.8), (16, 1.0), (40, 1.6)):
    night += arr(g_img.filter(ImageFilter.GaussianBlur(r))) * k
night[eyes] = [255, 240, 170]

yy = np.arange(BH)[:, None, None] / BH
middle = np.zeros((BH, BW, 3), np.float32) + (np.array([20, 24, 46]) * (1 - yy) + np.array([34, 30, 58]) * yy)
bg = middle.copy()
mask_l = Image.new("L", (BW, BH), 0)
ImageDraw.Draw(mask_l).polygon(left + [(0, BH), (0, 0)], fill=255)
mask_r = Image.new("L", (BW, BH), 0)
ImageDraw.Draw(mask_r).polygon(right + [(BW, BH), (BW, 0)], fill=255)
full_day = np.zeros_like(bg)
full_day[:, :760] = day
full_night = np.zeros_like(bg)
full_night[:, 1160:] = night
ml, mr = arr(mask_l)[..., None] / 255, arr(mask_r)[..., None] / 255
bg = bg * (1 - ml) + full_day * ml
bg = bg * (1 - mr) + full_night * mr
xx = np.arange(BW)[None, :, None]
bg *= np.clip(1 - 0.35 * (np.abs(xx - BW / 2) / (BW / 2)) ** 3, 0.5, 1)
banner = to_img(bg).convert("RGBA")
for line in (left, right):
    c = Image.new("RGBA", (BW, BH), (0, 0, 0, 0))
    ImageDraw.Draw(c).line(line, fill=(120, 240, 255, 255), width=8, joint="curve")
    gl = c.filter(ImageFilter.GaussianBlur(12))
    banner = Image.alpha_composite(Image.alpha_composite(banner, gl), gl)
    core = Image.new("RGBA", (BW, BH), (0, 0, 0, 0))
    ImageDraw.Draw(core).line(line, fill=(235, 255, 255, 255), width=3, joint="curve")
    banner = Image.alpha_composite(banner, core)

paste_center(banner, block_text("MOUNTAIN GIANT", 11, (255, 240, 120), (246, 128, 22), depth=7, outline=5), BW / 2, 100)
paste_center(banner, subtitle(4), BW / 2, 190, shadow=False)
for name, x, y, size in (("horn", 820, 340, 90), ("hammer", 960, 325, 170), ("armor", 1100, 330, 160)):
    paste_center(banner, item(name, size), x, y)
t = tag(4)
paste_center(banner, t, BW / 2, BH - 26, shadow=False)

os.makedirs(os.path.join(ROOT, "docs", "images"), exist_ok=True)
poster.convert("RGB").save(os.path.join(ROOT, "docs", "images", "poster.png"))
banner.convert("RGB").save(os.path.join(ROOT, "docs", "images", "banner.png"))
print("docs/images/poster.png, docs/images/banner.png")
