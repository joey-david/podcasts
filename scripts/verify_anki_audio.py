#!/usr/bin/env python3
"""Verify Podder's generated Anki audio pack and app-side safety invariant."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/anki_audio"
MANIFEST = ASSETS / "manifest.json"
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/joey/player/MainActivity.kt"


def fail(message: str) -> None:
    raise SystemExit(f"verify_anki_audio: {message}")


def main() -> int:
    if not MANIFEST.exists():
        fail(f"missing {MANIFEST}")

    payload = json.loads(MANIFEST.read_text(encoding="utf-8"))
    cards = payload.get("cards")
    if not isinstance(cards, list) or not cards:
        fail("manifest has no cards")

    audio_files = [card.get("audioFile", "") for card in cards]
    missing = [name for name in audio_files if not name or not (ASSETS / name).exists()]
    extras = sorted(path.name for path in ASSETS.glob("*.mp3") if path.name not in set(audio_files))
    if missing:
        fail(f"{len(missing)} manifest audio files are missing")
    if extras:
        fail(f"{len(extras)} mp3 files are not referenced by manifest")

    japanese_bad = [
        card.get("cardId")
        for card in cards
        if card.get("category") == "japanese"
        and not str(card.get("spokenText", "")).startswith("The meaning of ")
    ]
    if japanese_bad:
        fail(f"{len(japanese_bad)} Japanese cards do not use the expected phrase")

    japanese_voice_bad = []
    for card in cards:
        if card.get("category") != "japanese":
            continue
        segments = card.get("ttsSegments")
        if (
            not isinstance(segments, list)
            or len(segments) != 3
            or not str(segments[1].get("voice", "")).startswith("ja-JP-")
            or segments[1].get("text") != card.get("title")
        ):
            japanese_voice_bad.append(card.get("cardId"))
    if japanese_voice_bad:
        fail(f"{len(japanese_voice_bad)} Japanese cards do not use a dedicated ja-JP word segment")

    source = MAIN_ACTIVITY.read_text(encoding="utf-8")
    anki_block = source[source.find("private object AnkiDroidContract") :]
    forbidden = [
        r"contentResolver\.update",
        r"contentResolver\.insert",
        r"ReviewInfo",
        r"answer_ease",
        r"time_taken",
        r"buried",
        r"suspended",
    ]
    for pattern in forbidden:
        if re.search(pattern, anki_block):
            fail(f"forbidden AnkiDroid mutation/review token found: {pattern}")

    print(
        "ok",
        f"cards={len(cards)}",
        f"due={payload.get('dueCount')}",
        f"mp3={len(audio_files)}",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
