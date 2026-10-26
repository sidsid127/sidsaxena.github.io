"""Draws the launcher icon at every density into res/drawable-*/ic_launcher.png."""
import os, sys
from PIL import Image, ImageDraw

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res")
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
S = 512
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((16, 16, S - 16, S - 16), radius=110, fill="#2E5E4E")
# page
d.rounded_rectangle((120, 96, 392, 416), radius=28, fill="#F6F4EF")
for i, y in enumerate((180, 252, 324)):
    d.ellipse((150, y - 16, 182, y + 16), outline="#2E5E4E", width=8)
    d.rounded_rectangle((204, y - 8, 360, y + 8), radius=8, fill="#C9C4B8")
# check mark on the first line
d.line((156, 180, 166, 192, 192, 160), fill="#E07A3F", width=14, joint="curve")
for name, px in sizes.items():
    out = os.path.join(root, "drawable-" + name)
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
