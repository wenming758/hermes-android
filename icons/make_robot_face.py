# -*- coding: utf-8 -*-
"""Render the in-app robot mascot (transparent PNG) from the icon artwork."""
import os
from PIL import Image
import generate_icons as gi

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "HermesApp", "app", "src", "main", "res", "drawable-nodpi")


def main(size=512, pad=12):
    art = gi.draw_art_layer(1024)
    bbox = art.getbbox()
    crop = art.crop(bbox)
    inner = size - pad * 2
    scale = min(inner / crop.width, inner / crop.height)
    crop = crop.resize(
        (max(1, int(round(crop.width * scale))), max(1, int(round(crop.height * scale)))),
        Image.LANCZOS,
    )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(crop, ((size - crop.width) // 2, (size - crop.height) // 2))
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "robot_face.png")
    canvas.save(path)
    print(path)


if __name__ == "__main__":
    main()
