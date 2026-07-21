"""Generate adaptive-icon foreground PNGs from the existing launcher artwork.

The original ic_launcher.png is a polished rounded-tile logo the user liked.
We reuse that exact artwork as the adaptive foreground (scaled into the safe
zone) so it renders identically but works as a single notification icon.
"""
import os
from PIL import Image, ImageDraw

BASE = "app/src/main/res"
# density -> (source launcher px, adaptive foreground px [=108dp])
DENSITIES = {
    "mdpi":    (48, 108),
    "hdpi":    (72, 162),
    "xhdpi":   (96, 216),
    "xxhdpi":  (144, 324),
    "xxxhdpi": (192, 432),
}
# Fraction of the 108dp canvas the artwork should occupy (rest = safe-zone bleed)
CONTENT_SCALE = 0.80

# Highest-res source for best quality when upscaling
SRC = Image.open(os.path.join(BASE, "mipmap-xxxhdpi", "ic_launcher.png")).convert("RGBA")
w, h = SRC.size

# The square's corners contain white anti-aliasing outside the rounded tile.
# Round them off so only the clean dark tile + logo remains.
def round_corners(img, radius_frac=0.235):
    r = int(round(min(img.size) * radius_frac))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                           radius=r, fill=255)
    out = img.copy()
    a = out.getchannel("A")
    a = Image.composite(a, Image.new("L", img.size, 0), mask)
    out.putalpha(a)
    return out

SRC = round_corners(SRC)

def px(x, y):
    return SRC.getpixel((x, y))

print("corners:",
      px(0, 0), px(w - 1, 0), px(0, h - 1), px(w - 1, h - 1))
print("edge samples:",
      px(w // 2, int(h * 0.05)), px(int(w * 0.05), h // 2),
      px(w // 2, int(h * 0.95)))

# Pick the tile background color: darkest fully-opaque pixel among edge samples
candidates = [px(w // 2, int(h * 0.04)), px(int(w * 0.04), h // 2),
              px(w // 2, int(h * 0.96)), px(int(w * 0.96), h // 2)]
opaque = [c for c in candidates if c[3] > 200]
bg = min(opaque, key=lambda c: c[0] + c[1] + c[2]) if opaque else (8, 7, 15, 255)
print("chosen bg (RGBA):", bg)
print("bg hex: #{:02X}{:02X}{:02X}".format(bg[0], bg[1], bg[2]))

for dens, (src_px, fg_px) in DENSITIES.items():
    canvas = Image.new("RGBA", (fg_px, fg_px), (0, 0, 0, 0))
    content = int(round(fg_px * CONTENT_SCALE))
    art = SRC.resize((content, content), Image.LANCZOS)
    off = (fg_px - content) // 2
    canvas.alpha_composite(art, (off, off))
    out_dir = os.path.join(BASE, "mipmap-" + dens)
    out_path = os.path.join(out_dir, "ic_launcher_fg.png")
    canvas.save(out_path)
    print("wrote", out_path, canvas.size)

print("done")
