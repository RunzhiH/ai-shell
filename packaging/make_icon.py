from PIL import Image, ImageDraw, ImageFilter
import numpy as np

S = 2048  # supersample canvas

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def v_gradient(size, top, bottom):
    h, w = size
    ys = np.linspace(0, 1, h)
    col = np.zeros((h, w, 3), dtype=np.uint8)
    for i, t in enumerate(ys):
        c = lerp(top, bottom, t)
        col[i, :, :] = c
    return Image.fromarray(col, "RGB")

def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0]-1, size[1]-1], radius=radius, fill=255)
    return m

# ---------- background: rounded square with diagonal-ish gradient ----------
bg = v_gradient((S, S), (13, 17, 34), (37, 99, 235))   # slate-950 -> blue-600
mask = rounded_mask((S, S), S // 7)
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
img.paste(bg, (0, 0), mask)

d = ImageDraw.Draw(img)

# soft top highlight
hl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
hd = ImageDraw.Draw(hl)
hd.rounded_rectangle([S*0.06, S*0.05, S*0.94, S*0.30], radius=S//14, fill=(255, 255, 255, 26))
hl = hl.filter(ImageFilter.GaussianBlur(S*0.03))
img = Image.alpha_composite(img, hl)
d = ImageDraw.Draw(img)

# ---------- terminal window ----------
p = int(S * 0.135)          # padding
w = S - 2 * p
r = int(S * 0.055)
# shadow
sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
sd = ImageDraw.Draw(sh)
sd.rounded_rectangle([p, p + S*0.03, p + w, p + w + S*0.03], radius=r, fill=(0, 0, 0, 110))
sh = sh.filter(ImageFilter.GaussianBlur(S*0.02))
img = Image.alpha_composite(img, sh)
d = ImageDraw.Draw(img)
# window body
d.rounded_rectangle([p, p, p + w, p + w], radius=r, fill=(15, 23, 42, 255))
# window border highlight
d.rounded_rectangle([p, p, p + w, p + w], radius=r, outline=(148, 163, 184, 160), width=int(S*0.006))
# title bar
tb_h = int(S * 0.105)
d.rounded_rectangle([p, p, p + w, p + tb_h], radius=r, fill=(30, 41, 59, 255))
d.rectangle([p, p + tb_h // 2, p + w, p + tb_h], fill=(30, 41, 59, 255))
# three window dots
dot = int(S * 0.028)
gy = p + tb_h // 2
for i, c in enumerate([(239, 68, 68), (234, 179, 8), (34, 197, 94)]):
    gx = p + int(S * 0.05) + i * int(S * 0.075)
    d.ellipse([gx, gy - dot, gx + dot * 2, gy + dot], fill=c + (255,))

# ---------- big prompt ">_" ----------
lw = int(S * 0.030)          # line width
bx = p + int(S * 0.105)
by = p + tb_h + int(S * 0.165)
hh = int(S * 0.17)           # prompt height
cw = int(S * 0.115)          # char width
green = (52, 211, 153, 255)
# ">"
x0, y0 = bx, by
x1, y1 = bx + cw, by + hh // 2
x2, y0b = bx, by + hh
d.line([x0, y0, x1, y1], fill=green, width=lw)
d.line([x1, y1, x2, y0b], fill=green, width=lw)
# join smoothing circles at vertex
d.ellipse([x1 - lw//2, y1 - lw//2, x1 + lw//2, y1 + lw//2], fill=green)
d.ellipse([x0 - lw//2, y0 - lw//2, x0 + lw//2, y0 + lw//2], fill=green)
d.ellipse([x2 - lw//2, y0b - lw//2, x2 + lw//2, y0b + lw//2], fill=green)
# cursor "_"
cx = bx + cw + int(S * 0.08)
d.rounded_rectangle([cx, by + hh - lw*2, cx + int(S*0.10), by + hh], radius=lw//3, fill=green)

# ---------- AI sparkle (4-point star, gradient-ish) on window top-right ----------
sp_cx, sp_cy = p + w - int(S * 0.115), p + tb_h + int(S * 0.135)
R = int(S * 0.115)
r2 = int(S * 0.030)
pts = []
for k in range(8):
    ang = -np.pi / 2 + k * np.pi / 4
    rad = R if k % 2 == 0 else r2
    pts.append((sp_cx + rad * np.cos(ang), sp_cy + rad * np.sin(ang)))
star = Image.new("RGBA", (S, S), (0, 0, 0, 0))
sd = ImageDraw.Draw(star)
sd.polygon(pts, fill=(34, 211, 238, 255))
star = star.filter(ImageFilter.GaussianBlur(1))
img = Image.alpha_composite(img, star)
d = ImageDraw.Draw(img)
d.polygon(pts, outline=(255, 255, 255, 120))

# ---------- export ----------
base = img.resize((512, 512), Image.LANCZOS)
icon_sizes = [16, 24, 32, 48, 64, 128, 256]
imgs = [img.resize((s, s), Image.LANCZOS) for s in icon_sizes]
imgs[0].save(r"E:\workspace\ai-shell\packaging\app-icon.ico", format="ICO",
             sizes=[(s, s) for s in icon_sizes], append_images=imgs[1:])
# pngs for JavaFX window icon
base.resize((256, 256), Image.LANCZOS).save(r"E:\workspace\ai-shell\packaging\app-icon-256.png")
base.resize((64, 64), Image.LANCZOS).save(r"E:\workspace\ai-shell\packaging\app-icon-64.png")
base.resize((32, 32), Image.LANCZOS).save(r"E:\workspace\ai-shell\packaging\app-icon-32.png")
base.resize((16, 16), Image.LANCZOS).save(r"E:\workspace\ai-shell\packaging\app-icon-16.png")
print("icon v2 saved")
