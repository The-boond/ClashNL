from __future__ import annotations

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "Branding" / "ClashNL-logo-1024.png"
RESOURCES = ROOT / "app" / "src" / "main" / "res"


def main() -> None:
    source = Image.open(SOURCE).convert("RGB")
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in sizes.items():
        destination = RESOURCES / folder
        destination.mkdir(parents=True, exist_ok=True)
        icon = source.resize((size, size), Image.Resampling.LANCZOS)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            output = destination / name
            icon.save(output, format="PNG", optimize=True)
            print(output)

    play_store_icon = source.resize((512, 512), Image.Resampling.LANCZOS)
    play_store_paths = [
        ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png",
        ROOT
        / "app"
        / "src"
        / "other"
        / "play"
        / "listings"
        / "en-US"
        / "graphics"
        / "icon"
        / "ic_launcher-playstore.png",
    ]
    for play_store in play_store_paths:
        play_store.parent.mkdir(parents=True, exist_ok=True)
        play_store_icon.save(play_store, format="PNG", optimize=True)
        print(play_store)


if __name__ == "__main__":
    main()
