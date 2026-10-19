#!/usr/bin/env python3
"""Regenerate building's Android icons from the supplied artwork (requires Pillow)."""

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "branding/building"
RES = ROOT / "app/ide-android/src/main/res"
USE = Path("/tmp/use_icons")
BACKGROUND = "#141414"
DENSITIES = {
    "mdpi": (1.0, 48),
    "hdpi": (1.5, 72),
    "xhdpi": (2.0, 96),
    "xxhdpi": (3.0, 144),
    "xxxhdpi": (4.0, 192),
}


def inset(image, canvas, artwork):
    result = Image.new("RGBA", (canvas, canvas))
    scaled = image.resize((artwork, artwork), Image.Resampling.LANCZOS)
    result.alpha_composite(scaled, ((canvas - artwork) // 2,) * 2)
    return result


def main():
    dark = Image.open(BRAND / "icon-dark.png").convert("RGBA")
    clear = Image.open(BRAND / "icon-clear-dark.png").convert("RGBA")
    # Themed launcher artwork follows the color source; alpha preserves dark cutouts.
    luminance = ImageOps.grayscale(dark)
    alpha = luminance.point(lambda n: max(0, min(255, round((n - 55) * 255 / 160))))
    mono = Image.new("RGBA", dark.size, "white")
    mono.putalpha(alpha)

    # Extract play emblem from the bottom control area
    button = dark.crop((170, 350, 345, 505))
    gray = button.convert("L")
    tri_mask = Image.new("L", button.size, 0)
    for y in range(45, 140):
        for x in range(35, 145):
            val = gray.getpixel((x, y))
            if val > 120:
                tri_mask.putpixel((x, y), min(255, int((val - 120) * 255 / 80)))
    glyph = Image.new("RGBA", button.size, "white")
    glyph.putalpha(tri_mask)
    glyph = glyph.crop(glyph.getbbox())

    for density, (scale, px) in DENSITIES.items():
        mipmap = RES / f"mipmap-{density}"
        mipmap.mkdir(exist_ok=True)
        size = round(108 * scale)
        art_size = round(66 * scale)

        # Legacy square and round icons
        sq_src = USE / f"sq_{px}.png"
        if sq_src.exists():
            legacy = Image.open(sq_src).convert("RGBA")
        else:
            legacy = dark.resize((px, px), Image.Resampling.LANCZOS)
        legacy.save(mipmap / "ic_launcher.webp", format="WEBP", lossless=True)

        rd_src = USE / f"rd_{px}.png"
        if rd_src.exists():
            legacy_round = Image.open(rd_src).convert("RGBA")
        else:
            legacy_round = clear.resize((px, px), Image.Resampling.LANCZOS)
        legacy_round.save(mipmap / "ic_launcher_round.webp", format="WEBP", lossless=True)

        # Adaptive icon layers
        background = Image.new("RGBA", (size, size), BACKGROUND)
        background.save(mipmap / "ic_launcher_background.webp", format="WEBP", lossless=True)

        foreground = inset(dark, size, art_size)
        foreground.save(mipmap / "ic_launcher_foreground.webp", format="WEBP", lossless=True)

        inset(mono, size, art_size).save(mipmap / "ic_launcher_monochrome.webp", format="WEBP", lossless=True)

        # Notification status icon
        drawable = RES / f"drawable-{density}"
        drawable.mkdir(exist_ok=True)
        stat_size = round(24 * scale)
        stat_fit = round(20 * scale)
        small = ImageOps.contain(glyph, (stat_fit, stat_fit), Image.Resampling.LANCZOS)
        notification = Image.new("RGBA", (stat_size, stat_size), (0, 0, 0, 0))
        notification.alpha_composite(small, ((stat_size - small.width) // 2, (stat_size - small.height) // 2))
        notification.save(drawable / "ic_stat_building.webp", format="WEBP", lossless=True)

    store = Image.new("RGBA", dark.size, BACKGROUND)
    store.alpha_composite(dark)
    store.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS).save(BRAND / "play-store-icon.png", optimize=True)
    print("Generated adaptive, monochrome, legacy, round, notification and Play Store icons.")


if __name__ == "__main__":
    main()
