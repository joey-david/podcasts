#!/usr/bin/env python3
"""Export Anki cards as spoken audio for Podder.

This reads via AnkiConnect only. It never calls answerCards, easeCards, or any
review mutation endpoint, so generated playback cannot advance review state.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path


ANKI_HELPER = Path("/home/joey/.codex/skills/anki-connect/scripts")
DEFAULT_OUTPUT = Path("app/src/main/assets/anki_audio")
DEFAULT_VOICE = "en-US-AvaMultilingualNeural"
PODDER_AUDIO_RE = re.compile(
    r"\s*<div class=['\"]podder-audio['\"]>\[sound:[^\]]+\]</div>\s*",
    flags=re.I,
)


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


def make_spoken_text(deck_name: str, front_html: str, back_html: str) -> tuple[str, str, str]:
    front_text = div_text(front_html, "japadict-front") or html_to_text(front_html)
    meaning = div_text(back_html, "japadict-meaning")
    back_text = meaning or html_to_text(back_html)

    if "japanese" in deck_name.lower() or meaning:
        word = front_text
        translation = meaning or back_text
        return word, translation, f"The meaning of {word} is {translation}."

    title = front_text
    spoken = f"{front_text}. The answer is {back_text}."
    return title, back_text, spoken


def safe_stem(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-")
    return value[:80] or "card"


def chunked(values: list[int], size: int) -> list[list[int]]:
    return [values[index : index + size] for index in range(0, len(values), size)]


def collect_cards(query: str, limit: int | None) -> list[CardAudio]:
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
            title, answer, spoken = make_spoken_text(info["deckName"], front, back)
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
                    is_due=info["cardId"] in due_ids,
                    audio_file=stem,
                    back_html=back,
                )
            )
    return cards


def embed_audio_in_anki(cards: list[CardAudio], output_dir: Path, sync: bool) -> None:
    updated = 0
    skipped = 0
    seen_notes: set[int] = set()
    for index, card in enumerate(cards, start=1):
        if card.note_id in seen_notes:
            continue
        seen_notes.add(card.note_id)
        audio_path = output_dir / card.audio_file
        if not audio_path.exists():
            continue

        media_name = f"podder-{card.audio_file}"
        encoded = base64.b64encode(audio_path.read_bytes()).decode("ascii")

        clean_back = PODDER_AUDIO_RE.sub("", card.back_html).strip()
        next_back = f"{clean_back}<div class='podder-audio'>[sound:{media_name}]</div>"

        for attempt in range(1, 4):
            try:
                anki_invoke("storeMediaFile", filename=media_name, data=encoded)
                if next_back == card.back_html:
                    skipped += 1
                else:
                    anki_invoke(
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
    print(f"Embedded audio into {updated} Anki notes; skipped {skipped} already-linked notes")
    if sync:
        anki_invoke("sync")
        print("Triggered Anki sync")


async def synthesize_one(card: CardAudio, output_dir: Path, voice: str, index: int, total: int) -> None:
    import edge_tts

    target = output_dir / card.audio_file
    communicate = edge_tts.Communicate(card.spoken_text, voice=voice)
    await communicate.save(str(target))
    print(f"[{index}/{total}] {target.name}")


async def synthesize(cards: list[CardAudio], output_dir: Path, voice: str, force: bool, jobs: int) -> None:
    try:
        import edge_tts
    except ImportError as exc:
        raise SystemExit(
            "edge-tts is not installed. Run: python3 -m pip install --user edge-tts"
        ) from exc

    missing = [
        (index, card)
        for index, card in enumerate(cards, start=1)
        if force or not (output_dir / card.audio_file).exists()
    ]
    semaphore = asyncio.Semaphore(jobs)

    async def run(index: int, card: CardAudio) -> None:
        async with semaphore:
            await synthesize_one(card, output_dir, voice, index, len(cards))

    await asyncio.gather(*(run(index, card) for index, card in missing))


def write_manifest(cards: list[CardAudio], output_dir: Path, query: str, voice: str) -> None:
    payload = {
        "schemaVersion": 1,
        "source": "AnkiConnect",
        "query": query,
        "voice": voice,
        "count": len(cards),
        "dueCount": sum(1 for card in cards if card.is_due),
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
    cards = collect_cards(args.query, args.limit)
    write_manifest(cards, args.output, args.query, args.voice)
    print(f"Wrote {len(cards)} manifest entries to {args.output / 'manifest.json'}")
    if not args.manifest_only:
        asyncio.run(synthesize(cards, args.output, args.voice, args.force, args.jobs))
    if args.embed_in_anki:
        embed_audio_in_anki(cards, args.output, args.sync)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
