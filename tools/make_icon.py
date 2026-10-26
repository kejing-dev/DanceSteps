"""Dance Steps launcher icon export: the 512 PNG of the adaptive icon (res/drawable/ic_launcher_foreground.xml
on @color/ic_launcher_background), drawn from the same shapes, plus the xxxhdpi legacy PNG (192).

    python tools/make_icon.py <export-512.png>
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
WINE = (0x8C, 0x1C, 0x3A, 255)
WHITE = (255, 255, 255, 217)
CORAL = (0xFF, 0x7A, 0x59, 255)
CYAN = (0x2E, 0xC4, 0xD6, 255)
GOLD = (0xFF, 0xC9, 0x4D, 255)


def ellipse(d, cx, cy, rx, ry, fill):
    d.ellipse(((cx - rx) * K, (cy - ry) * K, (cx + rx) * K, (cy + ry) * K), fill=fill)


def rect_outline(d, x0, y0, x1, y1, width, fill):
    d.rounded_rectangle((x0 * K, y0 * K, x1 * K, y1 * K), radius=2 * K, outline=fill, width=int(width * K))


def draw():
    im = Image.new("RGBA", (S, S), WINE)
    d = ImageDraw.Draw(im)
    rect_outline(d, 34, 32, 74, 76, 3.2, WHITE)
    # the vector draws each ellipse from its left-middle point (cx - rx, cy): mirror that here
    ellipse(d, 44, 46, 6, 8.4, CORAL)
    ellipse(d, 43.6, 65, 4.3, 5, CORAL)
    ellipse(d, 64, 46, 6, 8.4, CYAN)
    ellipse(d, 64.4, 65, 4.3, 5, CYAN)
    ellipse(d, 74, 32, 5, 5, GOLD)
    return im


def main():
    full = draw()
    os.makedirs(os.path.join(RES, "mipmap-xxxhdpi"), exist_ok=True)
    full.resize((192, 192), Image.LANCZOS).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    if len(sys.argv) > 1:
        full.resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon written")


if __name__ == "__main__":
    main()
