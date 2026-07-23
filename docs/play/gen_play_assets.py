import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = r"C:\Users\JenilAgrawal\Documents\phoneBatteryAnalytics\android\app\src\main\res\font\dm_sans.ttf"
RAW  = r"C:\Users\JENILA~1\AppData\Local\Temp\claude\C--Users-JenilAgrawal-Documents-phoneBatteryAnalytics\6805739d-bfc7-4a4d-88af-29cd684bc744\scratchpad\play"
OUT  = r"C:\Users\JenilAgrawal\Documents\Cellspan-play-assets"
os.makedirs(OUT, exist_ok=True)

def rgb(h):
    h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def font(size, weight=None):
    f = ImageFont.truetype(FONT, size)
    if weight:
        try: f.set_variation_by_axes([weight])
        except Exception: pass
    return f

# ---------------------------------------------------------------- 512 icon
def make_icon():
    SS = 2048; s = SS / 108.0
    yy, xx = np.mgrid[0:SS, 0:SS]
    t = (xx + yy) / (2.0 * (SS - 1))
    c0, c1, c2 = map(lambda h: np.array(rgb(h), float), ('52796F', '3E5A55', '2F3E46'))
    t1 = np.clip(t / 0.55, 0, 1)[..., None]
    t2 = np.clip((t - 0.55) / 0.45, 0, 1)[..., None]
    grad = np.where(t[..., None] < 0.55, c0 * (1 - t1) + c1 * t1, c1 * (1 - t2) + c2 * t2).astype(np.uint8)
    img = Image.fromarray(np.dstack([grad, np.full((SS, SS), 255, np.uint8)]), 'RGBA')
    d = ImageDraw.Draw(img)
    light = rgb('EDF1EA')
    d.rounded_rectangle([30*s, 42*s, 78*s, 66*s], radius=5*s, fill=light)   # battery body
    d.rounded_rectangle([78*s, 50*s, 82*s, 58*s], radius=2*s, fill=light)   # terminal
    pts = [(16,54),(48,54),(52,54),(54,58),(58,44),(61,64),(64,52),(67,54),(92,54)]
    pts = [(x*s, y*s) for x, y in pts]
    pink = rgb('F06C9B'); w = int(round(3.6*s)); r = w/2
    d.line(pts, fill=pink, width=w, joint="curve")
    for (x, y) in pts:
        d.ellipse([x-r, y-r, x+r, y+r], fill=pink)
    img.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, 'icon-512.png'))
    print("icon-512.png")

# ---------------------------------------------------------------- screenshots
def rmask(size, radius):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0]-1, size[1]-1], radius, fill=255)
    return m

def make_shot(raw, caption, out):
    W, H = 1080, 1920
    yy, xx = np.mgrid[0:H, 0:W]
    t = (xx / W + yy / H) / 2.0
    c0, c1 = np.array(rgb('E9EEE3'), float), np.array(rgb('CBD8C6'), float)
    grad = (c0 * (1 - t[..., None]) + c1 * t[..., None]).astype(np.uint8)
    img = Image.fromarray(grad, 'RGB').convert('RGBA')
    d = ImageDraw.Draw(img)
    d.text((W/2, 78), "CELLSPAN", font=font(34, 600), fill=rgb('52796F'), anchor='mm')
    y = 152
    for ln in caption.split('\n'):
        d.text((W/2, y), ln, font=font(62, 700), fill=rgb('2F3E46'), anchor='ma'); y += 78

    shot = Image.open(raw).convert('RGBA')
    scale = 660 / shot.width
    sw, sh = int(shot.width * scale), int(shot.height * scale)
    shot = shot.resize((sw, sh), Image.LANCZOS)
    shot.putalpha(rmask((sw, sh), 36))
    pad = 14; bw, bh = sw + 2*pad, sh + 2*pad
    bx = (W - bw)//2; by = 350
    shadow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([bx, by, bx+bw, by+bh], 52, fill=(47, 62, 70, 90))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(22)))
    ImageDraw.Draw(img).rounded_rectangle([bx, by, bx+bw, by+bh], 52, fill=rgb('2F3E46'))
    img.alpha_composite(shot, (bx+pad, by+pad))
    img.convert('RGB').save(os.path.join(OUT, out), quality=95)
    print(out, img.size)

make_icon()
shots = [
    ("raw-now.png",      "Your battery,\nat a glance",   "01-now.png"),
    ("raw-health.png",   "Know its\nreal health",        "02-health.png"),
    ("raw-trends.png",   "Every trend,\nover time",       "03-trends.png"),
    ("raw-history.png",  "Every session,\nlogged",        "04-history.png"),
    ("raw-settings.png", "Private,\non your device",      "05-settings.png"),
]
for raw, cap, out in shots:
    make_shot(os.path.join(RAW, raw), cap, out)
print("DONE ->", OUT)
