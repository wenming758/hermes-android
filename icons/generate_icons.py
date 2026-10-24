# -*- coding: utf-8 -*-
"""Hermes Android icon generator. Renders a cartoon robot icon set from code."""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

BASE = 1024
SS = 4  # supersampling for the artwork

NAVY = (61, 58, 92, 255)
WHITE = (255, 255, 255, 255)
PINK = (255, 143, 168, 255)
BLUSH = (255, 184, 198, 235)
HILITE = (255, 255, 255, 255)

ROOT = os.path.dirname(os.path.abspath(__file__))


def gradient_bg(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    t = (xx + yy) / (2.0 * (size - 1))
    c1 = np.array([255, 248, 235], np.float32)
    c2 = np.array([212, 240, 255], np.float32)
    rgb = c1 + (c2 - c1) * t[..., None]
    cx = cy = (size - 1) / 2.0
    r = np.hypot(xx - cx, yy - cy) / (size * 0.62)
    g = np.clip(1.0 - r, 0, 1) ** 2 * 0.55
    rgb = rgb * (1 - g[..., None]) + 255.0 * g[..., None]
    rgba = np.dstack([np.clip(rgb, 0, 255), np.full((size, size), 255.0)])
    return Image.fromarray(rgba.astype(np.uint8), "RGBA")


def rounded_mask(size, radius_ratio=0.235):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    rad = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=rad, fill=255)
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def draw_art_layer(size, mono=False):
    """Cartoon robot: antenna, round head, eyes, blush, smile, chat pill."""
    W = size * SS
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = W / BASE

    def P(*vals):
        return [v * k for v in vals]

    def circle(cx, cy, r, fill=None, outline=None, width=0):
        d.ellipse(P(cx - r, cy - r, cx + r, cy + r), fill=fill, outline=outline, width=max(1, int(width * k)))

    def line_round(x1, y1, x2, y2, w, color):
        d.line(P(x1, y1, x2, y2), fill=color, width=max(1, int(w * k)))
        r = w / 2.0
        circle(x1, y1, r, fill=color)
        circle(x2, y2, r, fill=color)

    def ellipse_fill(cx, cy, rx, ry, color):
        d.ellipse(P(cx - rx, cy - ry, cx + rx, cy + ry), fill=color)

    def rrect(x1, y1, x2, y2, radius, fill=None, outline=None, width=0):
        d.rounded_rectangle(
            P(x1, y1, x2, y2), radius=radius * k, fill=fill, outline=outline,
            width=max(1, int(width * k)),
        )

    if mono:
        # Solid silhouette in white; eyes / smile / dots punched out later.
        line_round(512, 220, 512, 150, 26, WHITE)
        circle(512, 135, 47, fill=WHITE)
        circle(512, 478, 284, fill=WHITE)
        rrect(337, 775, 687, 921, 73, fill=WHITE)

        holes = Image.new("L", (W, W), 0)
        hd = ImageDraw.Draw(holes)
        for ex in (430, 594):
            hd.ellipse(P(ex - 34, 455 - 34, ex + 34, 455 + 34), fill=255)
        hd.arc(P(462, 505, 562, 590), start=15, end=165, fill=255, width=max(1, int(20 * k)))
        for dx in (428, 512, 596):
            hd.ellipse(P(dx - 20, 848 - 20, dx + 20, 848 + 20), fill=255)
        alpha = img.getchannel("A")
        alpha.paste(0, holes)
        img.putalpha(alpha)
        return img

    # Antenna
    line_round(512, 222, 512, 150, 26, NAVY)
    circle(512, 135, 47, fill=NAVY)
    circle(512, 135, 38, fill=PINK)
    ellipse_fill(500, 123, 11, 11, WHITE)

    # Head
    circle(512, 478, 270, fill=WHITE, outline=NAVY, width=28)

    # Eyes
    for ex in (430, 594):
        circle(ex, 455, 67, fill=NAVY)
        circle(ex, 455, 58, fill=WHITE, outline=NAVY, width=10)
        circle(ex, 455, 34, fill=NAVY)
        ellipse_fill(ex - 11, 442, 13, 13, WHITE)
        ellipse_fill(ex + 12, 464, 6, 6, WHITE)

    # Blush
    ellipse_fill(352, 548, 34, 16, BLUSH)
    ellipse_fill(672, 548, 34, 16, BLUSH)

    # Smile
    d.arc(P(462, 505, 562, 590), start=15, end=165, fill=NAVY, width=max(1, int(16 * k)))

    # Chat pill with typing dots
    rrect(350, 788, 674, 908, 60, fill=WHITE, outline=NAVY, width=26)
    for dx in (428, 512, 596):
        circle(dx, 848, 20, fill=PINK)

    return img


def render_icon(size, kind="legacy"):
    """kind: legacy | round | foreground | background | mono_foreground"""
    art = draw_art_layer(size, mono=(kind == "mono_foreground"))
    if kind in ("foreground", "mono_foreground"):
        art = art.resize((size, size), Image.LANCZOS)
        return art
    if kind == "background":
        return gradient_bg(size)

    art = art.resize((size, size), Image.LANCZOS)

    b = size * 2
    bg = gradient_bg(b)
    mask = circle_mask(b) if kind == "round" else rounded_mask(b)
    bg.putalpha(mask)
    bg = bg.resize((size, size), Image.LANCZOS)
    bg.alpha_composite(art)
    return bg


def fit_foreground(size=432, safe=264):
    """Scale the artwork so its content fits the adaptive-icon safe zone."""
    art = draw_art_layer(1024).resize((BASE, BASE), Image.LANCZOS)
    bbox = art.getbbox()
    content_h = bbox[3] - bbox[1]
    scale = safe / content_h
    crop = art.crop(bbox)
    new_w = max(1, int(round(crop.width * scale)))
    new_h = max(1, int(round(crop.height * scale)))
    crop = crop.resize((new_w, new_h), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(crop, ((size - new_w) // 2, (size - new_h) // 2))
    return canvas


def fit_mono(size=432, safe=264):
    art = draw_art_layer(1024, mono=True).resize((BASE, BASE), Image.LANCZOS)
    bbox = art.getbbox()
    content_h = bbox[3] - bbox[1]
    scale = safe / content_h
    crop = art.crop(bbox).resize(
        (max(1, int(round(art.crop(bbox).width * scale))), max(1, int(round(content_h * scale)))),
        Image.LANCZOS,
    )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(crop, ((size - crop.width) // 2, (size - crop.height) // 2))
    return canvas


def save(img, *parts):
    path = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    return path


def main():
    densities = {
        "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
    }
    for name, px in densities.items():
        save(render_icon(px, "legacy"), "android", f"mipmap-{name}", "ic_launcher.png")
        save(render_icon(px, "round"), "android", f"mipmap-{name}", "ic_launcher_round.png")

    save(fit_foreground(432, 280), "android", "mipmap-xxxhdpi", "ic_launcher_foreground.png")
    save(fit_mono(432, 280), "android", "mipmap-xxxhdpi", "ic_launcher_monochrome.png")
    save(render_icon(432, "background"), "android", "drawable-xxxhdpi", "ic_launcher_background.png")

    save(render_icon(1024, "legacy"), "master", "hermes_icon_1024.png")
    save(render_icon(512, "legacy"), "playstore", "hermes_icon_512.png")

    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = os.path.join(ROOT, "android", "mipmap-anydpi-v26", fname)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(xml)

    build_preview()
    print("done")


def load_font(size, bold=False):
    candidates = [
        r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\segoeui.ttf",
    ]
    for c in candidates:
        if os.path.exists(c):
            try:
                return ImageFont.truetype(c, size)
            except OSError:
                pass
    return ImageFont.load_default()


def masked_preview(icon, mask_kind):
    size = icon.size[0]
    if mask_kind == "circle":
        mask = circle_mask(size)
    elif mask_kind == "squircle":
        mask = rounded_mask(size, 0.30)
    else:
        mask = rounded_mask(size, 0.14)
    out = icon.copy()
    out.putalpha(Image.composite(out.getchannel("A"), Image.new("L", (size, size), 0), mask))
    return out


def build_preview():
    W, H = 1400, 860
    canvas = Image.new("RGBA", (W, H), (245, 247, 252, 255))
    d = ImageDraw.Draw(canvas)
    title = load_font(38, True)
    label = load_font(20)
    small = load_font(16)
    d.text((60, 40), "Hermes 安卓版 · 应用图标", font=title, fill=(45, 45, 70, 255))

    icon = render_icon(512, "legacy")
    canvas.alpha_composite(icon.resize((300, 300), Image.LANCZOS), (60, 120))
    d.text((60, 440), "Play 商店 / 主图标 512px", font=label, fill=(90, 90, 112, 255))

    sizes = [192, 144, 96, 72, 48]
    x = 430
    y = 130
    for s in sizes:
        img = render_icon(s, "legacy")
        canvas.alpha_composite(img, (x, y + (192 - s) // 2))
        d.text((x, 340), f"{s}px", font=small, fill=(110, 110, 130, 255))
        x += s + 34
    d.text((430, 370), "各密度 mipmap（实际尺寸）", font=label, fill=(90, 90, 112, 255))

    d.text((60, 500), "自适应图标裁切预览（圆形 / 圆角方 / 方圆）", font=label, fill=(90, 90, 112, 255))
    fg = fit_foreground(432, 280)
    bg = render_icon(432, "background")
    adaptive = bg.copy()
    adaptive.alpha_composite(fg)
    x = 60
    for kind, name in (("circle", "圆形"), ("squircle", "圆角方"), ("rounded", "方圆")):
        p = masked_preview(adaptive, kind).resize((170, 170), Image.LANCZOS)
        canvas.alpha_composite(p, (x, 555))
        d.text((x, 735), name, font=small, fill=(110, 110, 130, 255))
        x += 205

    d.text((730, 500), "Android 13 主题单色图标", font=label, fill=(90, 90, 112, 255))
    mono = Image.new("RGBA", (170, 170), (32, 32, 48, 255))
    mono.alpha_composite(fit_mono(432, 280).resize((170, 170), Image.LANCZOS))
    canvas.alpha_composite(mono, (730, 555))
    d.text((925, 555), "系统会按壁纸\n自动着色", font=small, fill=(110, 110, 130, 255))

    d.text((60, 790), "输出目录：icons/android（直接复制到 app/src/main/res）", font=small, fill=(140, 140, 158, 255))

    save(canvas, "preview", "preview_all.png")


if __name__ == "__main__":
    main()
