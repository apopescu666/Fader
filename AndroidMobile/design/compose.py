from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

SHOTS = "shots"
names = [
    ("library.png",  "Bibliotecă",            "surse, căutare, redate recent"),
    ("songs.png",    "Listă melodii",         "10.284 piste, fără blocaje"),
    ("player.png",   "Acum se redă",          "controale mari, progres clar"),
    ("settings.png", "Setări",                "accesibilitate în prim-plan"),
]

W, H = 390, 844
S = 2
PW, PH = W * S, H * S
GAP, MARGIN, HEADER = 80, 100, 240
LABEL_H = 150

cols = len(names)
sheet_w = MARGIN * 2 + PW * cols + GAP * (cols - 1)
sheet_h = HEADER + PH + LABEL_H + MARGIN

def load_font(candidates, size):
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()

SANS = ["/System/Library/Fonts/SFNS.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/Supplemental/Arial.ttf"]

f_title  = load_font(SANS, 64)
f_sub    = load_font(SANS, 30)
f_label  = load_font(SANS, 34)
f_small  = load_font(SANS, 26)

canvas = Image.new("RGB", (sheet_w, sheet_h), (10, 10, 12))

# soft teal glow
glow = Image.new("L", (sheet_w // 4, sheet_h // 4), 0)
gd = ImageDraw.Draw(glow)
gd.ellipse([sheet_w // 12, sheet_h // 20, sheet_w // 5, sheet_h // 5], fill=55)
glow = glow.resize((sheet_w, sheet_h), Image.LANCZOS).filter(ImageFilter.GaussianBlur(120))
canvas = Image.composite(Image.new("RGB", canvas.size, (18, 42, 44)), canvas, glow)

d = ImageDraw.Draw(canvas)
d.text((MARGIN, MARGIN - 30), "Fader Mobil", font=f_title, fill=(255, 255, 255))
d.text((MARGIN, MARGIN + 52), "Direcție vizuală — iOS, un singur ecran principal, accesibil din start",
       font=f_sub, fill=(150, 160, 170))

x = MARGIN
for file, label, sub in names:
    phone = Image.open(os.path.join(SHOTS, file)).convert("RGBA")
    phone = phone.resize((PW, PH), Image.LANCZOS)

    # drop shadow
    sh = Image.new("RGBA", (PW + 120, PH + 120), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    sd.rounded_rectangle([60, 60, 60 + PW, 60 + PH], radius=54 * S, fill=(0, 0, 0, 170))
    sh = sh.filter(ImageFilter.GaussianBlur(40))

    y = HEADER
    canvas.paste(sh, (x - 60, y - 60 + 14), sh)
    canvas.paste(phone, (x, y), phone)

    d.text((x + 4, y + PH + 42), label, font=f_label, fill=(255, 255, 255))
    d.text((x + 4, y + PH + 92), sub, font=f_small, fill=(140, 150, 160))
    x += PW + GAP

canvas.save("fader-mockups.png", optimize=True)
print("saved", canvas.size)
