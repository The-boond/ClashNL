from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "SFI" / "Assets.xcassets" / "AppIcon.appiconset"
BRANDING = ROOT / "Branding" / "ClashNL-logo-1024.png"
SCALE = 3
SIZE = 1024
CANVAS = SIZE * SCALE


def scaled(points):
    return [(round(x * SCALE), round(y * SCALE)) for x, y in points]


def gradient(start, end):
    image = Image.new("RGB", (CANVAS, CANVAS))
    pixels = image.load()
    for y in range(CANVAS):
        ratio = y / (CANVAS - 1)
        color = tuple(round(start[i] * (1 - ratio) + end[i] * ratio) for i in range(3))
        for x in range(CANVAS):
            pixels[x, y] = color
    return image


def draw_path(draw, points, fill, width):
    draw.line(scaled(points), fill=fill, width=round(width * SCALE), joint="curve")


def build_icon(background_top, background_bottom, path_a, path_b, accent, tinted=False):
    base = gradient(background_top, background_bottom)

    glow = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    shield = scaled([(512, 142), (790, 245), (748, 653), (512, 860), (276, 653), (234, 245)])
    glow_draw.polygon(shield, fill=(*path_a[:3], 42))
    glow = glow.filter(ImageFilter.GaussianBlur(75 * SCALE))
    base = Image.alpha_composite(base.convert("RGBA"), glow)

    art = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(art)

    shield_outline = scaled([(512, 142), (790, 245), (748, 653), (512, 860), (276, 653), (234, 245), (512, 142)])
    draw.line(shield_outline, fill=(255, 255, 255, 42), width=8 * SCALE, joint="curve")

    left_route = [(336, 688), (336, 330), (688, 688)]
    right_route = [(688, 688), (688, 330)]
    draw_path(draw, left_route, path_a, 74)
    draw_path(draw, right_route, path_b, 74)

    for point, radius, color in [
        ((336, 688), 47, path_a),
        ((512, 509), 48, accent),
        ((688, 330), 47, path_b),
    ]:
        x, y = point
        box = [(x - radius, y - radius), (x + radius, y + radius)]
        draw.ellipse(scaled(box), fill=color)
        inner = radius * 0.42
        inner_box = [(x - inner, y - inner), (x + inner, y + inner)]
        draw.ellipse(scaled(inner_box), fill=(245, 252, 255, 255) if not tinted else (23, 29, 40, 255))

    highlight = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    hdraw = ImageDraw.Draw(highlight)
    hdraw.ellipse(scaled([(175, 65), (850, 580)]), fill=(255, 255, 255, 18))
    highlight = highlight.filter(ImageFilter.GaussianBlur(90 * SCALE))

    result = Image.alpha_composite(base, highlight)
    result = Image.alpha_composite(result, art)
    return result.convert("RGB").resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    BRANDING.parent.mkdir(parents=True, exist_ok=True)
    variants = {
        "1024.png": ((8, 24, 43), (11, 54, 67), (32, 225, 207, 255), (37, 166, 240, 255), (255, 177, 74, 255), False),
        "1024-dark.png": ((3, 10, 22), (4, 28, 43), (55, 239, 218, 255), (65, 182, 255, 255), (255, 184, 77, 255), False),
        "1024-tinted.png": ((226, 235, 242), (176, 195, 210), (35, 47, 64, 255), (35, 47, 64, 255), (35, 47, 64, 255), True),
    }
    for name, parameters in variants.items():
        icon = build_icon(*parameters)
        icon.save(DESTINATION / name, format="PNG", optimize=True)
        if name == "1024.png":
            icon.save(BRANDING, format="PNG", optimize=True)
        print(DESTINATION / name)


if __name__ == "__main__":
    main()
