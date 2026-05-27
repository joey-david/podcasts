#!/usr/bin/env python3
"""Verify Podder's generated Anki audio pack and app-side safety invariant."""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AUDIO_EXPORT = ROOT / "generated/anki_audio"
MANIFEST = AUDIO_EXPORT / "manifest.json"
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/joey/player/MainActivity.kt"
FORMULA_MARKER_RE = re.compile(
    r"(\[/?(?:latex|\$\$)]|\$\$|\\frac|\\sum|\\int|\\sqrt|\\begin|\\end|[∑∫√∞≈≤≥])",
    re.I,
)
PODDER_AUDIO_RE = re.compile(
    r"\s*<div class=['\"]podder-audio['\"]>\[sound:[^\]]+\]</div>\s*",
    re.I,
)


def fail(message: str) -> None:
    raise SystemExit(f"verify_anki_audio: {message}")


def contains_formula(raw_html: str) -> bool:
    without_podder = PODDER_AUDIO_RE.sub("", raw_html or "")
    if FORMULA_MARKER_RE.search(without_podder):
        return True
    text = re.sub(r"<[^>]+>", " ", without_podder)
    return bool(re.search(r"\s(?:=|<=|>=|≈|≤|≥)\s|\w(?:=|<=|>=|≈|≤|≥)\w|(?<!\w)[A-Za-z]\s*[<>]\s*[A-Za-z0-9]", text))


def anki_invoke(action: str, **params):
    payload = json.dumps(
        {"action": action, "version": 5, "params": params},
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        "http://127.0.0.1:8765",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError:
        return None
    if body.get("error") is not None:
        fail(f"AnkiConnect error from {action}: {body['error']}")
    return body.get("result")


def verify_live_anki(cards: list[dict]) -> str:
    if anki_invoke("version") is None:
        return "live_anki=skipped"

    japanese_ids = [
        int(card["cardId"])
        for card in cards
        if card.get("category") == "japanese" and card.get("cardId")
    ]
    not_nice_ids = [
        int(card["cardId"])
        for card in cards
        if card.get("audioKind") == "not_nice" and card.get("cardId")
    ]
    playable_ids = [
        int(card["cardId"])
        for card in cards
        if card.get("ttsSegments") and card.get("category") != "japanese" and card.get("cardId")
    ]

    missing_readings = []
    for card_ids in (japanese_ids[index : index + 100] for index in range(0, len(japanese_ids), 100)):
        if not card_ids:
            continue
        infos = anki_invoke("cardsInfo", cards=card_ids)
        for info in infos or []:
            fields = info.get("fields", {})
            front = fields.get("Front", {}).get("value", "")
            back = fields.get("Back", {}).get("value", "")
            written_match = re.search(
                r"<div[^>]*class=['\"][^'\"]*japadict-front[^'\"]*['\"][^>]*>(.*?)</div>",
                front,
                re.I | re.S,
            )
            written = re.sub(r"<[^>]+>", "", written_match.group(1)).strip() if written_match else ""
            has_kanji = bool(re.search(r"[\u3400-\u9fff]", written))
            has_front_reading = "japadict-front-reading" in front
            has_back_reading = "japadict-reading" in back
            if not has_back_reading or (has_kanji and not has_front_reading):
                missing_readings.append(info.get("cardId"))
    if missing_readings:
        fail(f"{len(missing_readings)} live Japanese cards are missing kana readings")

    stale_not_nice_audio = []
    for card_ids in (not_nice_ids[index : index + 100] for index in range(0, len(not_nice_ids), 100)):
        if not card_ids:
            continue
        infos = anki_invoke("cardsInfo", cards=card_ids)
        for info in infos or []:
            back = info.get("fields", {}).get("Back", {}).get("value", "")
            if "podder-audio" in back or "[sound:podder-" in back:
                stale_not_nice_audio.append(info.get("cardId"))
    if stale_not_nice_audio:
        fail(f"{len(stale_not_nice_audio)} live not_nice cards still have Podder audio tags")

    playable_formula_audio = []
    for card_ids in (playable_ids[index : index + 100] for index in range(0, len(playable_ids), 100)):
        if not card_ids:
            continue
        infos = anki_invoke("cardsInfo", cards=card_ids)
        for info in infos or []:
            fields = info.get("fields", {})
            front = fields.get("Front", {}).get("value", "")
            back = fields.get("Back", {}).get("value", "")
            if contains_formula(f"{front}\n{back}"):
                playable_formula_audio.append(info.get("cardId"))
    if playable_formula_audio:
        fail(f"{len(playable_formula_audio)} live formula/equation cards still have Podder audio")

    return "live_anki=ok"


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
        and not re.match(r"^「[\u3040-\u30ffー]+」の意味は ", str(card.get("spokenText", "")))
    ]
    if japanese_bad:
        fail(f"{len(japanese_bad)} Japanese cards do not use the expected Japanese prompt")

    japanese_voice_bad = []
    for card in cards:
        if card.get("category") != "japanese":
            continue
        segments = card.get("ttsSegments")
        if (
            not isinstance(segments, list)
            or len(segments) != 2
            or not str(segments[0].get("voice", "")).startswith("ja-JP-")
            or not re.fullmatch(r"「[\u3040-\u30ffー]+」の意味は", str(segments[0].get("text", "")))
        ):
            japanese_voice_bad.append(card.get("cardId"))
    if japanese_voice_bad:
        fail(f"{len(japanese_voice_bad)} Japanese cards do not use a dedicated ja-JP kana prompt")

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

    formula_cards = [card for card in cards if "formula-heavy" in str(card.get("audioReason", ""))]
    if formula_cards and all(card.get("audioKind") != "not_nice" for card in formula_cards):
        fail("formula-heavy cards were not separated into not_nice")

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

    live_status = verify_live_anki(cards)

    print(
        "ok",
        f"cards={len(cards)}",
        f"playable={len(playable_cards)}",
        f"due={payload.get('dueCount')}",
        f"mp3={len(audio_files)}",
        f"kinds={payload.get('audioKindCounts')}",
        live_status,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
