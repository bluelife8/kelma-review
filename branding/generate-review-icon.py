#!/usr/bin/env python3
"""Generate Kelma Review launcher and installer icons from the company mark."""

import math
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
COMPANY_ICON = ROOT / "branding/kelma-company-icon.png"
MASTER_ICON = ROOT / "branding/kelma-review-icon.png"
DESKTOP_RESOURCES = ROOT / "desktopApp/src/main/resources"
IOS_ICON = ROOT / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"


def star_points(center_x: float, center_y: float, outer: float, inner: float) -> list[tuple[float, float]]:
    points = []
    for index in range(10):
        radius = outer if index % 2 == 0 else inner
        angle = math.radians(-90 + index * 36)
        points.append((center_x + math.cos(angle) * radius, center_y + math.sin(angle) * radius))
    return points


def create_master() -> Image.Image:
    base = Image.open(COMPANY_ICON).convert("RGB").resize((2048, 2048), Image.Resampling.LANCZOS)
    canvas = base.convert("RGBA")
    card = Image.new("RGBA", (860, 540), (0, 0, 0, 0))
    draw = ImageDraw.Draw(card)
    draw.rounded_rectangle((20, 20, 840, 520), radius=54, fill="#FAF8EC", outline="#CDAE67", width=28)
    draw.polygon(star_points(750, 425, 58, 27), fill="#49AA64", outline="#287D43", width=8)
    draw.line((205, 175, 700, 175), fill="#49AA64", width=34)
    draw.line((135, 275, 760, 275), fill="#49AA64", width=34)
    draw.line((135, 375, 625, 375), fill="#49AA64", width=34)
    card = card.rotate(-7, resample=Image.Resampling.BICUBIC, expand=True)

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow_piece = Image.new("RGBA", card.size, (0, 0, 0, 0))
    shadow_piece.putalpha(card.getchannel("A"))
    shadow_piece = shadow_piece.filter(ImageFilter.GaussianBlur(28))
    position = (900, 1120)
    shadow.alpha_composite(shadow_piece, (position[0] + 26, position[1] + 34))
    canvas = Image.alpha_composite(canvas, shadow)
    canvas.alpha_composite(card, position)
    return canvas.resize((1024, 1024), Image.Resampling.LANCZOS).convert("RGB")


def write_png_icons(master: Image.Image) -> None:
    MASTER_ICON.parent.mkdir(parents=True, exist_ok=True)
    DESKTOP_RESOURCES.mkdir(parents=True, exist_ok=True)
    master.save(MASTER_ICON, optimize=True)
    master.save(IOS_ICON, optimize=True)
    master.save(DESKTOP_RESOURCES / "icon.png", optimize=True)
    master.save(
        DESKTOP_RESOURCES / "icon.ico",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )

    legacy_sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    adaptive_sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for density, size in legacy_sizes.items():
        directory = ROOT / f"androidApp/src/main/res/mipmap-{density}"
        icon = master.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
        icon.save(directory / "ic_launcher.png", optimize=True)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        icon.putalpha(mask)
        icon.save(directory / "ic_launcher_round.png", optimize=True)
    for density, size in adaptive_sizes.items():
        directory = ROOT / f"androidApp/src/main/res/mipmap-{density}"
        master.resize((size, size), Image.Resampling.LANCZOS).save(
            directory / "ic_launcher_foreground.png",
            optimize=True,
        )


def write_icns() -> None:
    icon_specs = {
        "icon_16x16.png": 16,
        "icon_16x16@2x.png": 32,
        "icon_32x32.png": 32,
        "icon_32x32@2x.png": 64,
        "icon_128x128.png": 128,
        "icon_128x128@2x.png": 256,
        "icon_256x256.png": 256,
        "icon_256x256@2x.png": 512,
        "icon_512x512.png": 512,
        "icon_512x512@2x.png": 1024,
    }
    master = Image.open(MASTER_ICON)
    with tempfile.TemporaryDirectory() as temporary:
        iconset = Path(temporary) / "KelmaReview.iconset"
        iconset.mkdir()
        for name, size in icon_specs.items():
            master.resize((size, size), Image.Resampling.LANCZOS).save(iconset / name)
        subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(DESKTOP_RESOURCES / "icon.icns")],
            check=True,
        )


def main() -> None:
    master = create_master()
    write_png_icons(master)
    write_icns()


if __name__ == "__main__":
    main()
