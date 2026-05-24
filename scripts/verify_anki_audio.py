#!/usr/bin/env python3
"""Verify Podder's generated Anki audio pack and app-side safety invariant."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AUDIO_EXPORT = ROOT / "generated/anki_audio"
MANIFEST = AUDIO_EXPORT / "manifest.json"
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

    playable_cards = [card for card in cards if card.get("ttsSegments")]
    audio_files = [card.get("audioFile", "") for card in playable_cards]
    missing = [name for name in audio_files if not name or not (AUDIO_EXPORT / name).exists()]
    extras = sorted(path.name for path in AUDIO_EXPORT.glob("*.mp3") if path.name not in set(audio_files))
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

    unknown_audio_kind = [
        card.get("cardId")
        for card in cards
        if card.get("audioKind") not in {"nice", "conceptual", "not_nice"}
    ]
    if unknown_audio_kind:
        fail(f"{len(unknown_audio_kind)} cards have unknown audioKind")

    bad_formula_audio = [
        card.get("cardId")
        for card in cards
        if card.get("audioKind") == "conceptual"
        and not str(card.get("spokenText", "")).startswith(("Formula card:", "Formula-heavy card:"))
        and "check the exact notation visually" not in str(card.get("spokenText", "")).lower()
    ]
    if bad_formula_audio:
        fail(f"{len(bad_formula_audio)} conceptual formula cards do not use readable audio text")

    not_nice_with_audio = [
        card.get("cardId")
        for card in cards
        if card.get("audioKind") == "not_nice" and card.get("ttsSegments")
    ]
    if not_nice_with_audio:
        fail(f"{len(not_nice_with_audio)} not_nice cards still have TTS segments")

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
    required = [
        "resolveAnkiDroidMediaUri",
        "resolveIndexedAnkiDroidMediaUri",
        "MediaStore.Files.getContentUri",
        "asset:///anki_audio/",
    ]
    for token in required:
        if token not in anki_block:
            fail(f"missing AnkiDroid media playback token: {token}")
    if "tts://anki" in anki_block or "TextToSpeech" in anki_block:
        fail("Anki playback must use synced MP3 media, not Android TextToSpeech")

    print(
        "ok",
        f"cards={len(cards)}",
        f"playable={len(playable_cards)}",
        f"due={payload.get('dueCount')}",
        f"mp3={len(audio_files)}",
        f"kinds={payload.get('audioKindCounts')}",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
