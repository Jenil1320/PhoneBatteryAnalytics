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

# Replace the captured system status bar with a clean synthetic one, so no
# notification icons (which reveal installed apps), real time, or battery/wifi
# state remain — nothing that could point back to a specific phone.
def clean_statusbar(img):
    d = ImageDraw.Draw(img)
    W = img.width
    bg = img.getpixel((20, 135))[:3]
    d.rectangle([0, 0, W, 108], fill=bg)
    dark = (26, 43, 48)
    d.text((48, 28), "9:41", font=font(46, 600), fill=dark)
    bx, by = W - 210, 74
    for i in range(4):
        h = 12 + i * 8
        d.rounded_rectangle([bx + i * 17, by - h, bx + i * 17 + 11, by], radius=2, fill=dark)
    ax, ay = W - 118, 44
    d.rounded_rectangle([ax, ay, ax + 78, ay + 38], radius=9, outline=dark, width=4)
    d.rounded_rectangle([ax + 7, ay + 7, ax + 7 + 48, ay + 31], radius=3, fill=dark)
    d.rectangle([ax + 80, ay + 12, ax + 86, ay + 26], fill=dark)
    return img

def rmask(size, radius):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0]-1, size[1]-1], radius, fill=255)
    return m

def frame(rawname, caption, out):
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

    shot = clean_statusbar(Image.open(os.path.join(RAW, rawname)).convert('RGBA'))
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
    print(out)

# ---- feature graphic 1024x500 ----
def feature_graphic():
    W, H = 1024, 500
    yy, xx = np.mgrid[0:H, 0:W]
    t = (xx / W * 0.75 + yy / H * 0.25)
    c0, c1, c2 = map(lambda h: np.array(rgb(h), float), ('52796F', '3E5A55', '2F3E46'))
    t1 = np.clip(t / 0.55, 0, 1)[..., None]; t2 = np.clip((t - 0.55) / 0.45, 0, 1)[..., None]
    grad = np.where(t[..., None] < 0.55, c0*(1-t1)+c1*t1, c1*(1-t2)+c2*t2).astype(np.uint8)
    img = Image.fromarray(grad, 'RGB').convert('RGBA')
    icon = Image.open(os.path.join(OUT, 'icon-512.png')).convert('RGBA').resize((300, 300), Image.LANCZOS)
    icon.putalpha(rmask((300, 300), 64))
    img.alpha_composite(icon, (80, 100))
    d = ImageDraw.Draw(img)
    d.text((470, 175), "Cellspan", font=font(120, 700), fill=rgb('EDF1EA'), anchor='lm')
    d.text((474, 300), "Battery health, on your device.", font=font(40, 500), fill=rgb('CAD2C5'), anchor='lm')
    img.convert('RGB').save(os.path.join(OUT, 'feature-graphic.png'), quality=95)
    print("feature-graphic.png")

# remove the old un-sanitized numbered screenshots
for f in os.listdir(OUT):
    if f.startswith(('01-', '02-', '03-', '04-', '05-')):
        os.remove(os.path.join(OUT, f))

frame("raw-now.png",      "Your battery,\nat a glance", "01-now.png")
frame("raw-health.png",   "Know its\nreal health",      "02-health.png")
frame("raw-trends.png",   "Every trend,\nover time",     "03-trends.png")
frame("raw-settings.png", "Charge alarm\n& export",     "04-settings.png")
feature_graphic()
print("DONE ->", OUT)
