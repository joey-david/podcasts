#!/usr/bin/env python3
"""Export Anki cards as spoken audio for Podder.

This reads via AnkiConnect only. It never calls answerCards, easeCards, or any
review mutation endpoint, so generated playback cannot advance review state.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import html
import json
import re
import shlex
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
import subprocess
import tempfile


ANKI_HELPER = Path("/home/joey/.codex/skills/anki-connect/scripts")
DEFAULT_OUTPUT = Path("generated/anki_audio")
DEFAULT_VOICE = "en-US-AvaMultilingualNeural"
DEFAULT_JAPANESE_VOICE = "ja-JP-NanamiNeural"
PODDER_AUDIO_RE = re.compile(
    r"\s*<div class=['\"]podder-audio['\"]>\[sound:[^\]]+\]</div>\s*",
    flags=re.I,
)
LATEX_MARKER_RE = re.compile(r"(\[/?(?:latex|\$\$)]|\$\$|\\\(|\\\[|\\[A-Za-z]+|[_^{}]|[=<>∑∫√∞λσμπ≈≤≥])")
WORD_RE = re.compile(r"[A-Za-z]{3,}")


class TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        if data.strip():
            self.parts.append(data.strip())

    def text(self) -> str:
        return clean_text(" ".join(self.parts))


@dataclass
class CardAudio:
    card_id: int
    note_id: int
    deck_name: str
    category: str
    title: str
    prompt_text: str
    answer_text: str
    spoken_text: str
    tts_segments: list[dict[str, str]]
    audio_kind: str
    audio_reason: str
    is_due: bool
    audio_file: str
    back_html: str

    def manifest_entry(self) -> dict:
        return {
            "cardId": self.card_id,
            "noteId": self.note_id,
            "deckName": self.deck_name,
            "category": self.category,
            "title": self.title,
            "promptText": self.prompt_text,
            "answerText": self.answer_text,
            "spokenText": self.spoken_text,
            "ttsSegments": self.tts_segments,
            "audioKind": self.audio_kind,
            "audioReason": self.audio_reason,
            "isDue": self.is_due,
            "audioFile": self.audio_file,
        }


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
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not reach AnkiConnect: {exc}") from exc
    if body.get("error") is not None:
        raise RuntimeError(str(body["error"]))
    return body.get("result")


def anki_invoke_retry(action: str, **params):
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            return anki_invoke(action, **params)
        except Exception as exc:
            last_error = exc
            if attempt == 4:
                break
            time.sleep(attempt)
    raise RuntimeError(f"{action} failed after retries: {last_error}") from last_error


def clean_text(value: str) -> str:
    value = html.unescape(value or "")
    value = PODDER_AUDIO_RE.sub(" ", value)
    value = re.sub(r"\[sound:[^\]]+\]", " ", value, flags=re.I)
    value = re.sub(r"\[\$\$\](.*?)\[/\$\$\]", r"\1", value, flags=re.S)
    value = re.sub(r"<br\s*/?>", " ", value, flags=re.I)
    value = re.sub(r"<[^>]+>", " ", value)
    value = re.sub(r"\\(?:quad|qquad|mathrm|mathbb|frac|sqrt|Pr|Var|lambda|sigma|mu|dots|in)\b", " ", value)
    value = re.sub(r"[_^{}\\]", " ", value)
    value = re.sub(r"\s+", " ", value).strip()
    return value


def html_to_text(value: str) -> str:
    parser = TextExtractor()
    parser.feed(value or "")
    return parser.text()


def div_text(raw_html: str, class_name: str) -> str | None:
    pattern = rf"<div[^>]*class=['\"][^'\"]*{re.escape(class_name)}[^'\"]*['\"][^>]*>(.*?)</div>"
    match = re.search(pattern, raw_html or "", flags=re.I | re.S)
    if not match:
        return None
    return html_to_text(match.group(1))


def formula_density(raw_html: str, clean: str) -> float:
    compact = re.sub(r"\s+", "", raw_html or clean)
    if not compact:
        return 0.0
    return len(LATEX_MARKER_RE.findall(raw_html or clean)) / len(compact)


def looks_formula_heavy(raw_html: str, clean: str) -> bool:
    if re.search(r"(\[/?latex]|\[\$\$]|\[/\$\$]|\$\$|\\frac|\\sum|\\int|\\sqrt|\\begin|\\end)", raw_html or "", re.I):
        return True
    if re.search(r"\s(?:=|<=|>=|≈|≤|≥)\s|\w(?:=|<=|>=|≈|≤|≥)\w", clean or ""):
        return True
    if re.search(r"\b(formula|equation)\b", clean or "", re.I) and re.search(r"[=<>^_{}\\]|\\[A-Za-z]+", raw_html or clean):
        return True
    symbol_count = len(re.findall(r"[=<>^_{}\\+\-*/|∑∫√∞≈≤≥]", raw_html or clean))
    word_count = len(WORD_RE.findall(clean))
    return symbol_count >= 8 and symbol_count > word_count * 1.5


def looks_audio_unfriendly(raw_html: str, clean: str) -> tuple[bool, str]:
    if not clean:
        return True, "empty text"
    if len(clean) > 900:
        return True, "too long for one audio card"
    if clean.count(";") + clean.count(",") > 18 and clean.count(".") < 2:
        return True, "dense list"
    if formula_density(raw_html, clean) > 0.08 and len(WORD_RE.findall(clean)) < 8:
        return True, "mostly notation"
    return False, ""


def conceptual_formula_audio(front_text: str, back_text: str) -> str:
    topic = front_text.rstrip(".?")
    lower = topic.lower()
    if any(token in lower for token in ("intuition", "conceptually", "why", "what is")):
        return f"{topic}. Explain the idea in words, then check the exact notation visually in Anki."
    if any(token in lower for token in ("pmf", "density", "loss", "objective", "bound", "theorem", "inequality")):
        return f"Formula card: {topic}. Recall what each term means and the overall shape of the expression. Check the exact symbols visually in Anki."
    return f"Formula-heavy card: {topic}. Use this audio as a prompt to recall the exact visual formula in Anki; do not rely on spoken notation for this one."


def make_spoken_text(
    deck_name: str,
    front_html: str,
    back_html: str,
    english_voice: str,
    japanese_voice: str,
) -> tuple[str, str, str, list[dict[str, str]], str, str]:
    front_text = div_text(front_html, "japadict-front") or html_to_text(front_html)
    reading = div_text(back_html, "japadict-reading") or div_text(front_html, "japadict-front-reading") or ""
    meaning = div_text(back_html, "japadict-meaning")
    explicit_audio = div_text(back_html, "podder-audio-text") or div_text(front_html, "podder-audio-text")
    back_text = meaning or html_to_text(back_html)

    if "japanese" in deck_name.lower() or meaning:
        word = front_text
        spoken_word = reading or word
        translation = meaning or back_text
        title = f"{word} ({reading})" if reading and reading != word else word
        japanese_prompt = f"「{spoken_word}」の意味は"
        spoken = f"{japanese_prompt} {translation}."
        segments = [
            {"voice": japanese_voice, "text": japanese_prompt},
            {"voice": english_voice, "text": f"{translation}."},
        ]
        return title, translation, spoken, segments, "nice", "Japanese kana prompt plus English translation"

    title = front_text
    front_formula = looks_formula_heavy(front_html, front_text)
    back_formula = looks_formula_heavy(back_html, back_text)
    unfriendly, reason = looks_audio_unfriendly(front_html + "\n" + back_html, f"{front_text} {back_text}")
    if explicit_audio:
        spoken = explicit_audio
        return title, back_text, spoken, [{"voice": english_voice, "text": spoken}], "conceptual", "explicit readable audio text"
    if front_formula or back_formula:
        spoken = conceptual_formula_audio(front_text, back_text)
        return title, back_text, spoken, [], "not_nice", "formula-heavy card has no explicit readable audio text"
    if unfriendly:
        spoken = f"{front_text}."
        return title, back_text, spoken, [], "not_nice", reason
    spoken = f"{front_text}. The answer is {back_text}."
    return title, back_text, spoken, [{"voice": english_voice, "text": spoken}], "nice", "plain text card"


def safe_stem(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-")
    return value[:80] or "card"


def chunked(values: list[int], size: int) -> list[list[int]]:
    return [values[index : index + size] for index in range(0, len(values), size)]


def collect_cards(query: str, limit: int | None, english_voice: str, japanese_voice: str) -> list[CardAudio]:
    all_card_ids = anki_invoke("findCards", query=query)
    if limit:
        all_card_ids = all_card_ids[:limit]
    due_ids = set(anki_invoke("findCards", query=f"({query}) is:due"))
    cards: list[CardAudio] = []

    for card_ids in chunked(all_card_ids, 100):
        for info in anki_invoke("cardsInfo", cards=card_ids):
            fields = info.get("fields", {})
            front = fields.get("Front", {}).get("value", "")
            back = fields.get("Back", {}).get("value", "")
            title, answer, spoken, segments, audio_kind, audio_reason = make_spoken_text(
                info["deckName"],
                front,
                back,
                english_voice,
                japanese_voice,
            )
            deck_name = info["deckName"]
            category = deck_name.split("::", 1)[0]
            stem = f"{category}-{info['cardId']}-{safe_stem(title)}.mp3"
            cards.append(
                CardAudio(
                    card_id=info["cardId"],
                    note_id=info["note"],
                    deck_name=deck_name,
                    category=category,
                    title=title,
                    prompt_text=html_to_text(front),
                    answer_text=answer,
                    spoken_text=spoken,
                    tts_segments=segments,
                    audio_kind=audio_kind,
                    audio_reason=audio_reason,
                    is_due=info["cardId"] in due_ids,
                    audio_file=stem,
                    back_html=back,
                )
            )
    return cards


def embed_audio_in_anki(cards: list[CardAudio], output_dir: Path, sync: bool) -> None:
    updated = 0
    removed = 0
    skipped = 0
    seen_notes: set[int] = set()
    for index, card in enumerate(cards, start=1):
        if card.note_id in seen_notes:
            continue
        seen_notes.add(card.note_id)
        if not card.tts_segments:
            clean_back = PODDER_AUDIO_RE.sub("", card.back_html).strip()
            if clean_back != card.back_html:
                anki_invoke_retry(
                    "updateNoteFields",
                    note={
                        "id": card.note_id,
                        "fields": {
                            "Back": clean_back,
                        },
                    },
                )
                removed += 1
                print(f"[{index}/{len(cards)}] removed Podder audio from {card.title}")
            else:
                skipped += 1
            continue
        audio_path = output_dir / card.audio_file
        if not audio_path.exists() or audio_path.stat().st_size == 0:
            continue

        media_name = f"podder-{card.audio_file}"
        encoded = base64.b64encode(audio_path.read_bytes()).decode("ascii")

        clean_back = PODDER_AUDIO_RE.sub("", card.back_html).strip()
        next_back = f"{clean_back}<div class='podder-audio'>[sound:{media_name}]</div>"

        for attempt in range(1, 4):
            try:
                anki_invoke_retry("storeMediaFile", filename=media_name, data=encoded)
                if next_back == card.back_html:
                    skipped += 1
                else:
                    anki_invoke_retry(
                        "updateNoteFields",
                        note={
                            "id": card.note_id,
                            "fields": {
                                "Back": next_back,
                            },
                        },
                    )
                    updated += 1
                print(f"[{index}/{len(cards)}] stored {media_name}")
                break
            except Exception as exc:
                if attempt == 3:
                    raise
                print(f"[{index}/{len(cards)}] retry {attempt} after {exc}", file=sys.stderr)
                time.sleep(attempt)
        time.sleep(0.02)
    print(f"Embedded audio into {updated} Anki notes; removed {removed} audio tags; skipped {skipped} already-linked notes")
    if sync:
        anki_invoke("sync")
        print("Triggered Anki sync")


async def save_tts(text: str, voice: str, target: Path) -> None:
    import edge_tts

    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            communicate = edge_tts.Communicate(text, voice=voice)
            await communicate.save(str(target))
            return
        except Exception as exc:
            last_error = exc
            target.unlink(missing_ok=True)
            if attempt == 4:
                break
            await asyncio.sleep(attempt * 2)
    raise RuntimeError(f"TTS failed for voice={voice}: {last_error}") from last_error


def concat_mp3(parts: list[Path], target: Path) -> None:
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as list_file:
        list_path = Path(list_file.name)
        for part in parts:
            list_file.write(f"file {shlex.quote(str(part))}\n")
    try:
        subprocess.run(
            [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(list_path),
                "-c",
                "copy",
                str(target),
            ],
            check=True,
        )
    finally:
        list_path.unlink(missing_ok=True)


def tts_signature(card: CardAudio) -> str:
    payload = json.dumps(
        {"schemaVersion": 1, "segments": card.tts_segments},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def tts_is_current(card: CardAudio, output_dir: Path) -> bool:
    target = output_dir / card.audio_file
    meta = target.with_suffix(target.suffix + ".json")
    if not target.exists() or target.stat().st_size == 0 or not meta.exists():
        return False
    try:
        payload = json.loads(meta.read_text(encoding="utf-8"))
    except Exception:
        return False
    return payload.get("signature") == tts_signature(card)


def write_tts_meta(card: CardAudio, output_dir: Path) -> None:
    target = output_dir / card.audio_file
    meta = target.with_suffix(target.suffix + ".json")
    payload = {"signature": tts_signature(card), "segments": card.tts_segments}
    meta.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


async def synthesize_one(card: CardAudio, output_dir: Path, index: int, total: int) -> None:
    target = output_dir / card.audio_file
    if len(card.tts_segments) == 1:
        segment = card.tts_segments[0]
        await save_tts(segment["text"], segment["voice"], target)
    else:
        with tempfile.TemporaryDirectory(prefix=f"podder-tts-{card.card_id}-") as tmp:
            parts: list[Path] = []
            for segment_index, segment in enumerate(card.tts_segments):
                part = Path(tmp) / f"{segment_index:02d}.mp3"
                await save_tts(segment["text"], segment["voice"], part)
                parts.append(part)
            concat_mp3(parts, target)
    write_tts_meta(card, output_dir)
    print(f"[{index}/{total}] {target.name}")


async def synthesize(cards: list[CardAudio], output_dir: Path, force: bool, jobs: int) -> None:
    try:
        import edge_tts
    except ImportError as exc:
        raise SystemExit(
            "edge-tts is not installed. Run: python3 -m pip install --user edge-tts"
        ) from exc

    missing = [
        (index, card)
        for index, card in enumerate(cards, start=1)
        if card.tts_segments
        and (force or not tts_is_current(card, output_dir))
    ]
    semaphore = asyncio.Semaphore(jobs)

    async def run(index: int, card: CardAudio) -> None:
        async with semaphore:
            await synthesize_one(card, output_dir, index, len(cards))

    await asyncio.gather(*(run(index, card) for index, card in missing))


def prune_stale_audio(cards: list[CardAudio], output_dir: Path) -> None:
    playable = {card.audio_file for card in cards if card.tts_segments}
    for path in output_dir.glob("*.mp3"):
        if path.name not in playable:
            path.unlink()
            path.with_suffix(path.suffix + ".json").unlink(missing_ok=True)
    for path in output_dir.glob("*.mp3.json"):
        audio_name = path.name.removesuffix(".json")
        if audio_name not in playable:
            path.unlink()


def write_manifest(cards: list[CardAudio], output_dir: Path, query: str, voice: str, japanese_voice: str) -> None:
    payload = {
        "schemaVersion": 1,
        "source": "AnkiConnect",
        "query": query,
        "voice": voice,
        "japaneseVoice": japanese_voice,
        "count": len(cards),
        "dueCount": sum(1 for card in cards if card.is_due),
        "audioKindCounts": {
            kind: sum(1 for card in cards if card.audio_kind == kind)
            for kind in sorted({card.audio_kind for card in cards})
        },
        "cards": [card.manifest_entry() for card in cards],
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate Podder Anki audio assets.")
    parser.add_argument("--query", default="deck:*", help="Anki search query to export.")
    parser.add_argument("--limit", type=int, help="Limit cards for test exports.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--voice", default=DEFAULT_VOICE)
    parser.add_argument("--japanese-voice", default=DEFAULT_JAPANESE_VOICE)
    parser.add_argument("--force", action="store_true", help="Regenerate existing mp3 files.")
    parser.add_argument("--jobs", type=int, default=4, help="Concurrent TTS requests.")
    parser.add_argument("--manifest-only", action="store_true", help="Skip speech generation.")
    parser.add_argument(
        "--embed-in-anki",
        action="store_true",
        help="Store generated audio in Anki media and append a [sound:] tag to each Back field.",
    )
    parser.add_argument(
        "--sync",
        action="store_true",
        help="Run Anki sync after --embed-in-anki updates.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    cards = collect_cards(args.query, args.limit, args.voice, args.japanese_voice)
    prune_stale_audio(cards, args.output)
    write_manifest(cards, args.output, args.query, args.voice, args.japanese_voice)
    print(f"Wrote {len(cards)} manifest entries to {args.output / 'manifest.json'}")
    if not args.manifest_only:
        asyncio.run(synthesize(cards, args.output, args.force, args.jobs))
    if args.embed_in_anki:
        embed_audio_in_anki(cards, args.output, args.sync)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
