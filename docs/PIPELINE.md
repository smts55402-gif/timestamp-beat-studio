# Pipeline — Deterministic 1-Second Bucketing Spec

This document is the normative spec for how audio becomes a second-by-second
beat sheet. The implementation lives in `:core` (`SecondBucketizer`,
`TimestampFormat`); see `docs/CORE_API.md` for the frozen API.

## Whole-second convention

For an audio file of actual media duration `D` seconds (fractional):

- `durationSec = max(1, floor(D))` — the number of output records.
- Buckets are indexed `0 .. durationSec - 1`, strictly chronological.
- A 13:58.75 audio → 838 records, displayed `00:00` … `13:57`.
- **Every second exists.** No skipping, no merging, no deletion — including
  leading/trailing silence.

## MM:SS display rule

The only visible timestamp format is `MM:SS`:

- `0` → `00:00`, `61` → `01:01`, `837` → `13:57`.
- Hours roll into minutes: `MM` may exceed 59 (e.g. `75:03` for 4503 s).
- Regex: `^\d{2,}:\d{2}$` with the seconds part `< 60`.

## Word assignment rule (deterministic)

Each recognized word is assigned to **exactly one** bucket:

```text
bucket = floor(word.startSec), clamped to [0, durationSec - 1]
```

- The bucket is chosen by the word's **start** time only. A word starting at
  2.84 s belongs to bucket `00:02`; a word starting at 3.02 s belongs to
  `00:03`.
- A word starting at or beyond the media duration is clamped to the last
  bucket and reported as a **WARNING** — it is never dropped.
- Words must arrive in chronological order (non-decreasing `startSec`);
  out-of-order input is rejected (`IllegalArgumentException`).

## No-split guarantee

A word is atomic. If a word crosses a one-second boundary (e.g. starts at
1.84 s, ends at 2.15 s), the **complete word** goes to bucket `00:01`
(`floor(1.84) = 1`). Producing `00:01 "wo"` / `00:02 "rd"` is a defect.
Words in the same bucket are joined with single spaces in chronological
order; no individual word timestamps are shown.

## Internal precision vs display

| Layer | Content |
|---|---|
| Internal | `Word.startSec` / `endSec` (fractional, e.g. `2.84`), bucket indexes, inaudible ranges |
| Displayed / exported (TXT, MD, JSON) | `MM:SS` whole-second timestamps only |
| SRT only | `HH:MM:SS,mmm` cue timings, derived from internal precise timing |

Precise timing is an internal implementation detail used solely to place
words in the right bucket (and to build SRT cues). It is never shown in the
result screen, TXT/MD/JSON exports, or validation panel. This is not guessing
timestamps — bucketing always derives from real audio timing produced by the
transcription engine.

## SILENCE vs INAUDIBLE

```text
00:14 [SILENCE]     -- no speech detected in this second
00:21 [INAUDIBLE]   -- speech detected but not reliably recognizable
```

- `SILENCE`: bucket has no words and is not covered by an inaudible range.
- `INAUDIBLE`: bucket has no words but is covered by
  `TranscriptionResult.inaudibleRangesSec`. The engine reports these ranges;
  the bucketizer never invents them.
- Never guess words for `[INAUDIBLE]`. Never move neighboring speech into a
  silent/inaudible second.

## Bucket text reconstruction

- `SPEECH` bucket: `words.joinToString(" ")` — the exact recognized words,
  verbatim. No rewriting, summarizing, paraphrasing, grammar fixes, synonym
  replacement, or invented words.
- Whitespace may be normalized for clean display; punctuation is preserved
  where the engine produced it reliably.
- If the user manually corrects a second, the correction is stored separately
  (`correctedText`); the original words remain untouched and the display
  prefers the correction. Original vs corrected are never conflated.

## Reprocessing

Reprocessing reuses cached intermediates (`PcmAudio`, `TranscriptionResult`,
media duration) and re-runs bucketing → reconstruction → validation → export.
Manual corrections survive reprocessing (keyed by second index) unless the
user clears them.
