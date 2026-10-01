# Validation — Checks, WARNING vs FAIL, Coverage-Error Gate

Every processing run produces a `ValidationReport` from `:core`
(`PipelineValidator`). The validation panel in `:app` renders these checks
verbatim; check names are stable and the UI depends on them.

## The 7 checks

| # | Check name | What it verifies |
|---|---|---|
| 1 | `Audio Duration` | `timeline.durationSec == max(1, floor(audioDurationSec))` — the timeline covers the real media duration |
| 2 | `Every Second Generated` | `buckets.size == durationSec`, `buckets[i].second == i`, no gaps, strictly chronological |
| 3 | `Transcript Coverage` | Every input word assigned to exactly one bucket — 0 missing, 0 orphaned, 0 unassigned |
| 4 | `Duplicate Words` | No word index appears in two buckets — each word in exactly one place |
| 5 | `Invented Content` | Every `SPEECH` bucket's text equals its words joined verbatim; `[SILENCE]`/`[INAUDIBLE]` appear only on empty buckets |
| 6 | `Chronological Order` | Word indexes ascend within and across buckets (matches engine's chronological output) |
| 7 | `Timestamp Format` | Every `mmss(second)` passes `isValidMmss` (`^\d{2,}:\d{2}$`, seconds < 60) |

Each check reports `PASS`, `WARNING`, or `FAIL` plus a human-readable detail
string. The report's `overall` is `FAIL` if any check fails, else `WARNING`
if any warns, else `PASS`.

## What triggers WARNING vs FAIL

**WARNING (never FAIL) — processing continues, export allowed with badge:**

- **Clamped words** — one or more words started at/after the media duration
  and were clamped into the last bucket. Detail names the count. The words
  are kept, never dropped.
- **Empty transcript** — the engine returned zero words for the whole file.
  All buckets become `[SILENCE]` (or `[INAUDIBLE]` where ranges apply).
- **Fully silent audio** — valid result, but flagged so the user can confirm
  the input file was correct.

**FAIL — no PASS badge, export blocked until resolved:**

- Any missing word (`missingWords > 0`)
- Any duplicated word (`duplicateWords > 0`)
- Any invented segment (speech text not traceable to its bucket's words)
- Bucket count/index mismatch vs duration
- Chronological or timestamp-format violations

## The COVERAGE ERROR gate

If `missingWords > 0` **or** `duplicateWords > 0`, the report `overall` is
`FAIL` and the app **must**:

1. Display `COVERAGE ERROR — REVIEW REQUIRED` prominently on the result
   screen (not a toast, not a log line).
2. Refuse the PASS badge and block export until the user reprocesses or
   acknowledges the failure.

The app must never claim full coverage without actually running these checks.
Status is reported as measured: `PASS` / `WARNING` / `FAIL`. Marketing-style
claims such as "100% accurate" are banned — here and in the UI.

## Panel fields

The result screen's validation panel shows, at minimum:

```text
Audio Duration:        PASS
Every Second Generated: PASS
Transcript Coverage:   PASS
Missing Words:         0
Duplicate Words:       0
Invented Content:      0
Chronological Order:   PASS
Timestamp Format:      PASS
Silent Seconds:        <count>
Inaudible Sections:    <count>
Overall Status:        PASS
```
