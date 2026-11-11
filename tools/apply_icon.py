from PIL import Image
from pathlib import Path

src = Path(r"C:\Users\Administrator\.cursor\projects\c-Users-Administrator-Desktop-app\assets\sztu-icon-v2.png")
res = Path(r"C:\Users\Administrator\Desktop\app\app\src\main\res")
img = Image.open(src).convert("RGBA")

# Sample background blue from near corner (full-bleed icons are solid)
px = img.getpixel((20, 20))
if px[3] < 200:
    px = img.getpixel((img.width // 2, img.height // 2))
bg = (px[0], px[1], px[2], 255)
bg_hex = "#{:02X}{:02X}{:02X}".format(*bg[:3])
print("bg", bg_hex, "src", img.size)

SIZE = 1080
canvas = Image.new("RGBA", (SIZE, SIZE), bg)
full = img.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
canvas = Image.alpha_composite(canvas, full)
(res / "drawable").mkdir(parents=True, exist_ok=True)
canvas.save(res / "drawable" / "ic_launcher_foreground.png", optimize=True)

# Also write a distinct drawable used only as adaptive fg alias bump
canvas.save(res / "drawable" / "ic_launcher_fg_v2.png", optimize=True)

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, s in sizes.items():
    out_dir = res / folder
    out_dir.mkdir(parents=True, exist_ok=True)
    scaled = canvas.resize((s, s), Image.Resampling.LANCZOS)
    scaled.save(out_dir / "ic_launcher.png", optimize=True)
    scaled.save(out_dir / "ic_launcher_round.png", optimize=True)
    print(folder, "ok")

colors = res / "values" / "colors.xml"
text = colors.read_text(encoding="utf-8")
import re
text2 = re.sub(
    r'<color name="ic_launcher_bg">[^<]+</color>',
    f'<color name="ic_launcher_bg">{bg_hex}</color>',
    text,
)
colors.write_text(text2, encoding="utf-8")
print("done")
