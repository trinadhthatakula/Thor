#!/usr/bin/env python3
"""Build Play listing images from reviewed, unaltered app captures.

Requires Pillow. Run from any directory: python3 fastlane/scripts/build-listing-images.py
Edit the copy and captures here, then review the rendered images before publishing.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "web/src/assets/screenshots"
IMAGES = ROOT / "fastlane/metadata/android/en-US/images"
SHOTS = IMAGES / "phoneScreenshots"
def available_font(*paths: str) -> str:
    return next((path for path in paths if Path(path).exists()), paths[0])


BOLD = available_font(
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
)
REGULAR = available_font(
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
)

# Each caption describes the app state visible beneath it; text is for people
# browsing the listing, not a claim that Play indexes screenshot OCR.
SCREENS = [
    ("web/src/assets/screenshots/01-home-bento.png", "Android app manager", "See apps, status and quick actions"),
    ("web/src/assets/screenshots/02-freezer-active-and-frozen.png", "Freeze apps", "Keep data. Restore when you choose."),
    ("web/src/assets/screenshots/03-refusal-unsafe-system-app.png", "Debloat with guidance", "Unsafe system-app freezes are blocked"),
    ("fastlane/assets/captures/uad-recommended.png", "Filter system apps", "Review UAD recommendations before acting"),
    ("web/src/assets/screenshots/04-app-list-permission-chips.png", "Find any app", "Search and filter user and system apps"),
    ("web/src/assets/screenshots/05-settings-work-mode.png", "Root or rootless", "Choose Root, Shizuku or Dhizuku"),
    ("fastlane/assets/captures/backup-hub.png", "Back up and restore", "Save app bundles; Root can include private data"),
    ("web/src/assets/screenshots/06-extension-manager.png", "Verified extensions", "Add optional features on demand"),
]
W, H = 1280, 2560
INK = "#172019"
MUTED = "#415048"
LIME = "#D9FFAF"
BACKGROUNDS = ["#E6F2D8", "#E9EDFA", "#F5EDE2", "#E5EAFB", "#DDEFEA", "#F1E9F2", "#E9F2E1", "#EDF1DD"]


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def fit_font(draw: ImageDraw.ImageDraw, text: str, max_width: int, start: int) -> ImageFont.FreeTypeFont:
    size = start
    while draw.textbbox((0, 0), text, font=font(BOLD, size))[2] > max_width:
        size -= 2
    return font(BOLD, size)


def phone_card(canvas: Image.Image, shot: Image.Image, x: int, y: int, width: int) -> None:
    ratio = width / shot.width
    height = round(shot.height * ratio)
    screenshot = shot.convert("RGB").resize((width, height), Image.Resampling.LANCZOS)
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), radius=48, fill=255)

    shadow = Image.new("RGBA", canvas.size)
    ImageDraw.Draw(shadow).rounded_rectangle(
        (x + 4, y + 12, x + width + 4, y + height + 12), radius=56, fill=(15, 31, 22, 100)
    )
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(34)))
    canvas.paste(screenshot, (x, y), mask)
    ImageDraw.Draw(canvas).rounded_rectangle(
        (x - 2, y - 2, x + width + 1, y + height + 1), radius=50, outline="#C7D0C0", width=3
    )


def render_screens() -> None:
    SHOTS.mkdir(parents=True, exist_ok=True)
    for index, (source, headline, subline) in enumerate(SCREENS):
        canvas = Image.new("RGBA", (W, H), BACKGROUNDS[index])
        draw = ImageDraw.Draw(canvas)
        draw.rounded_rectangle((74, 62, 448, 124), radius=31, fill=INK)
        draw.text((99, 73), "THOR  /  APP MANAGER", font=font(BOLD, 27), fill=LIME)
        draw.text((1100, 75), f"{index + 1:02d} / {len(SCREENS):02d}", font=font(BOLD, 27), fill=MUTED)
        draw.text((74, 174), headline, font=fit_font(draw, headline, W - 148, 82), fill=INK)
        draw.text((76, 290), subline, font=font(REGULAR, 35), fill=MUTED)
        draw.rounded_rectangle((74, 370, 1206, 375), radius=3, fill="#A5C58E")
        shot = Image.open(ROOT / source)
        phone_card(canvas, shot, 158, 424, 964)
        canvas.convert("RGB").save(SHOTS / f"{index}.png", optimize=True)


def render_feature_graphic() -> None:
    canvas = Image.new("RGBA", (1024, 500), INK)
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((46, 44, 334, 91), radius=24, fill=LIME)
    draw.text((65, 53), "THOR APP MANAGER", font=font(BOLD, 22), fill=INK)
    draw.text((46, 132), "Manage apps.", font=font(BOLD, 62), fill="#F5F9EE")
    draw.text((46, 204), "Freeze bloat.", font=font(BOLD, 62), fill=LIME)
    draw.text((49, 327), "Root  •  Shizuku  •  Dhizuku", font=font(REGULAR, 29), fill="#E5F1D9")
    draw.text((49, 373), "Open source  •  No ads or trackers", font=font(REGULAR, 23), fill="#B6C5AF")
    shot = Image.open(SOURCES / "01-home-bento.png")
    # The graphic is intentionally a crop, not a full phone screenshot.
    thumb = shot.convert("RGB").resize((350, 758), Image.Resampling.LANCZOS)
    canvas.paste(thumb, (680, 20))
    draw.rectangle((664, 0, 678, 500), fill=LIME)
    canvas.convert("RGB").save(IMAGES / "featureGraphic.png", optimize=True)


if __name__ == "__main__":
    render_screens()
    render_feature_graphic()
