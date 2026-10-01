# Export Formats — TXT / MD / JSON / SRT

All four exporters live in `:core` (`Exporters`) and operate on the same
`SecondTimeline`. TXT/MD/JSON show **only** whole-second `MM:SS` timestamps;
only SRT uses precise timing (for subtitle cues).

## Filename convention

`safeFileName(projectName, exporter)` sanitizes the project name
(`"My Project!"` → `"My_Project"`, illegal filesystem characters removed)
and appends the suffix plus extension:

```text
My_Project_stage01.txt
My_Project_stage01.md
My_Project_stage01.json
My_Project_stage01.srt
```

## TXT (primary)

One line per second: `<MM:SS> <displayText>`. Silent seconds are kept.

```text
00:00 You're standing at the edge of a still pond.
00:01 It's about 40,000 years ago.
00:02 Somewhere in what's now southern France.
00:03 And here's the strange part.
00:04 You have never once in your entire life seen your own face.
00:05 Not in a mirror, not in a photo, not in anything.
00:06 You've probably checked your reflection five or six times
00:07 today without even registering it.
00:08 [SILENCE]
```

## MD

`# <projectName>` title, blank line, then the identical timeline inside a
fenced `text` code block (same line format as TXT).

```markdown
# My Project

```text
00:00 You're standing at the edge of a still pond.
00:01 It's about 40,000 years ago.
...
```
```

## JSON

Machine-readable; preserves display timestamp **and** internal metadata.
Status strings are lowercase (`speech` | `silence` | `inaudible`). Word
`start`/`end` keep full internal precision. `extras` is reserved for Stage 02
and is `{}` in Stage 01.

```json
{
  "project": "My Project",
  "durationSec": 838,
  "seconds": [
    {
      "timestamp": "00:12",
      "start_seconds": 12,
      "text": "Not in a mirror, not in a photo, not in anything.",
      "status": "speech",
      "source_word_indexes": [34, 35, 36, 37, 38, 39, 40, 41, 42, 43],
      "words": [
        { "text": "Not", "start": 72.10, "end": 72.31 },
        { "text": "in", "start": 72.32, "end": 72.44 }
      ],
      "extras": {}
    }
  ]
}
```

## SRT

Cues are built from **internal precise timing only**: runs of consecutive
`SPEECH` buckets merge into one cue; cue start = first word's `startSec`,
cue end = last word's `endSec`; text = joined words. Timing format is
`HH:MM:SS,mmm`. The primary beat sheet stays whole-second — only SRT exposes
precise timing, because subtitle players require it.

```srt
1
00:00:00,410 --> 00:00:07,720
You're standing at the edge of a still pond. It's about 40,000 years ago.
Somewhere in what's now southern France. And here's the strange part.

2
00:00:08,050 --> 00:00:12,900
You have never once in your entire life seen your own face.
```

Manual corrections (`correctedText`) are reflected in all four exports;
original words remain available in the JSON `words` array.
