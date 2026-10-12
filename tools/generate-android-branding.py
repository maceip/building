#!/usr/bin/env python3
"""Regenerate building's Android icons from the supplied artwork (requires Pillow)."""

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "branding/building"
RES = ROOT / "app/ide-android/src/main/res"
BACKGROUND = "#151716"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def inset(image, canvas, artwork):
    result = Image.new("RGBA", (canvas, canvas))
    scaled = image.resize((artwork, artwork), Image.Resampling.LANCZOS)
    result.alpha_composite(scaled, ((canvas - artwork) // 2,) * 2)
    return result


def main():
    dark = Image.open(BRAND / "icon-dark.png").convert("RGBA")
    clear = Image.open(BRAND / "icon-clear-dark.png").convert("RGBA")
    # Convert the neutral variant to a true white/alpha mask, not a solid square.
    luminance = ImageOps.grayscale(clear)
    alpha = luminance.point(lambda n: max(0, min(255, round((n - 55) * 255 / 160))))
    alpha = ImageChops.multiply(alpha, clear.getchannel("A"))
    mono = Image.new("RGBA", clear.size, "white")
    mono.putalpha(alpha)
    # At notification size the artwork's speech/sparkle emblem reads more clearly
    # than the complete stack. Preserve its dark star cutouts in the alpha mask.
    glyph = clear.crop((385, 76, 623, 290))
    glyph_alpha = ImageOps.grayscale(glyph).point(lambda n: max(0, min(255, round((n - 180) * 255 / 45))))
    glyph_alpha = ImageChops.multiply(glyph_alpha, glyph.getchannel("A"))
    glyph = Image.new("RGBA", glyph.size, "white")
    glyph.putalpha(glyph_alpha)
    glyph = glyph.crop(glyph.getbbox())
    for density, scale in DENSITIES.items():
        mipmap = RES / f"mipmap-{density}"
        mipmap.mkdir(exist_ok=True)
        size = round(108 * scale)
        foreground = inset(dark, size, round(66 * scale))
        background = Image.new("RGBA", (size, size), BACKGROUND)
        background.save(mipmap / "ic_launcher_background.png", optimize=True)
        foreground.save(mipmap / "ic_launcher_foreground.png", optimize=True)
        inset(mono, size, round(66 * scale)).save(mipmap / "ic_launcher_monochrome.png", optimize=True)
        background.alpha_composite(foreground)
        edge = round(18 * scale)
        legacy = background.crop((edge, edge, size - edge, size - edge))
        legacy = legacy.resize((round(48 * scale),) * 2, Image.Resampling.LANCZOS)
        legacy.save(mipmap / "ic_launcher.png", optimize=True)
        mask = Image.new("L", legacy.size)
        ImageDraw.Draw(mask).ellipse((0, 0, legacy.width - 1, legacy.height - 1), fill=255)
        legacy.putalpha(mask)
        legacy.save(mipmap / "ic_launcher_round.png", optimize=True)
        drawable = RES / f"drawable-{density}"
        drawable.mkdir(exist_ok=True)
        small = ImageOps.contain(glyph, (round(20 * scale),) * 2, Image.Resampling.LANCZOS)
        notification = Image.new("RGBA", (round(24 * scale),) * 2)
        notification.alpha_composite(small, ((notification.width - small.width) // 2, (notification.height - small.height) // 2))
        notification.save(drawable / "ic_stat_building.png", optimize=True)
    store = Image.new("RGBA", dark.size, BACKGROUND)
    store.alpha_composite(dark)
    store.convert("RGB").resize((512, 512), Image.Resampling.LANCZOS).save(BRAND / "play-store-icon.png", optimize=True)
    print("Generated adaptive, monochrome, legacy, round, notification and Play Store icons.")


if __name__ == "__main__":
    main()
