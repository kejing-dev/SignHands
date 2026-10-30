"""Raster icon layers (PICO 3D launcher icon_3d_layer_0/1 and the factory's icon.png) from the same shapes as
res/drawable/ic_launcher_foreground.xml: a white L-hand and a translucent copy offset up-right, on palm coral.
python -I tools/make_icon.py"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res" / "drawable"
FACTORY_ICON = Path("C:/Users/Admin/Downloads/spatial-app-factory/apps/sign-hands/icon.png")
N = 512
K = N / 108.0
CORAL = (0xE0, 0x71, 0x5A, 255)


def hand(draw, dx, dy, fill):
    def p(x, y):
        return ((x + dx) * K, (y + dy) * K)

    def rr(x0, y0, x1, y1, r):
        draw.rounded_rectangle([p(x0, y0), p(x1, y1)], radius=r * K, fill=fill)

    rr(38, 54, 70, 84, 6)  # palm
    rr(38, 24, 46, 58, 4)  # index finger up
    for cx in (47, 55, 63):  # folded fingers
        x, y = p(cx, 54)
        draw.ellipse([x - 4 * K, y - 4 * K, x + 4 * K, y + 4 * K], fill=fill)
    draw.polygon([p(64, 62), p(78, 51), p(84, 55), p(70, 68)], fill=fill)  # thumb out
    x, y = p(81, 53)
    draw.ellipse([x - 3.2 * K, y - 3.2 * K, x + 3.2 * K, y + 3.2 * K], fill=fill)


def glyph():
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    ghost = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    hand(ImageDraw.Draw(ghost), 5, -5, (255, 255, 255, 97))
    solid = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    hand(ImageDraw.Draw(solid), -3, 1, (255, 255, 255, 255))
    im.alpha_composite(ghost)
    im.alpha_composite(solid)
    return im


def main():
    bg = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    ImageDraw.Draw(bg).ellipse([0, 0, N - 1, N - 1], fill=CORAL)
    fg = glyph()
    bg.save(RES / "icon_3d_layer_0.png")
    fg.save(RES / "icon_3d_layer_1.png")
    full = Image.new("RGBA", (N, N), CORAL)
    full.alpha_composite(fg)
    FACTORY_ICON.parent.mkdir(parents=True, exist_ok=True)
    full.convert("RGB").save(FACTORY_ICON)
    print("icons written", FACTORY_ICON)


if __name__ == "__main__":
    main()
