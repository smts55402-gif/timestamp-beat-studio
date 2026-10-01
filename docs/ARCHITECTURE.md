# Architecture — Timestamp Beat Studio

## Module diagram

```text
┌──────────────────────────────────────────────────────────────┐
│  :app  — Android application (Kotlin + Jetpack Compose)      │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │ Home /      │  │ Result screen│  │ Audio player +      │  │
│  │ Processing  │  │ (MM:SS list, │  │ per-second          │  │
│  │ screens     │  │ validation   │  │ highlighting        │  │
│  │             │  │ panel, edits)│  │ (tap-to-seek)       │  │
│  └─────────────┘  └──────────────┘  └─────────────────────┘  │
│  Room: ProjectEntity / BucketEntity / CorrectionEntity      │
└───────────┬──────────────────────────────┬───────────────────┘
            │ TranscriptionEngine (core)   │  frozen contract
            │                              │  (docs/CORE_API.md)
   ┌────────▼─────────┐           ┌─────────▼───────────────────┐
   │    :whisper       │           │          :core              │
   │  Android library │           │  pure Kotlin/JVM            │
   │  JNI → whisper.cpp│          │  • Word / SecondBucket /    │
   │  (CMake + NDK)   │           │    SecondTimeline           │
   │                  │           │  • SecondBucketizer         │
   │  LocalEngine:    │           │  • PipelineValidator        │
   │  on-device STT,  │           │  • TimestampFormat (MM:SS)  │
   │  16 kHz mono PCM │           │  • Exporters (TXT/MD/       │
   │  in, words out   │           │    JSON/SRT)                │
   └──────────────────┘           └─────────────────────────────┘
                                              ▲
                                              │ same TranscriptionResult
                                              │ contract
                              ┌───────────────┴───────────────┐
                              │  server/  (optional, remote)  │
                              │  FastAPI + faster-whisper     │
                              │  HTTP: POST /transcribe       │
                              │  (see docs/SERVER_API.md)     │
                              └───────────────────────────────┘
```

**Dependency rule:** `:core` knows nothing about Android, JNI, or HTTP.
`:app` and `:whisper` both consume the frozen contract in
`docs/CORE_API.md`. The server mirrors the same `TranscriptionResult` shape
over HTTP so the pipeline downstream is identical regardless of engine.

## Data flow (Stage 01 pipeline)

```text
Audio file
  │ 1. Audio Validation      (format supported? duration > 0? readable?)
  ▼
  │ 2. Preprocessing         (decode → resample 16 kHz mono float PCM)
  ▼
  │ 3. Speech Recognition    (TranscriptionEngine.transcribe → words w/ internal timing)
  ▼
  │ 4. Word-Level Timing     (chronological Word list; startSec/endSec, fractional)
  ▼
  │ 5. Bucketing             (SecondBucketizer: bucket = floor(word.startSec))
  ▼
  │ 6. Bucket Reconstruction (join words per second; [SILENCE]/[INAUDIBLE] placeholders)
  ▼
  │ 7. Validation            (PipelineValidator: 7 checks → PASS/WARNING/FAIL)
  ▼
  │ 8. Export                (TXT / MD / JSON / SRT via Exporters)
  ▼
Second-by-second beat sheet
```

Manual corrections and reprocessing loop back into steps 5–7 using cached
intermediate data (word list, PCM, media duration) — see
`docs/PIPELINE.md`.

## Local vs remote engine design

Both engines implement the same `TranscriptionEngine` interface from `:core`:

```kotlin
suspend fun transcribe(
    pcm: PcmAudio,
    durationSec: Double,
    progress: (Float) -> Unit,
    isCancelled: () -> Boolean
): TranscriptionResult
```

| | Local (`:whisper`) | Remote (`server/`) |
|---|---|---|
| Runtime | whisper.cpp via JNI, on-device | FastAPI + faster-whisper, HTTP |
| Cost | Free, offline | Self-hosted, free |
| Progress | JNI callbacks → `progress` | Chunked response / polling → `progress` |
| Cancellation | `isCancelled` checked in native loop | `isCancelled` aborts the HTTP call |

The `:app` layer selects the engine at processing time. Everything after
`TranscriptionResult` (bucketing → validation → export) is engine-agnostic
and lives in `:core`, so a transcript from either engine produces an
identical-structure beat sheet.

## Room schema (`:app`)

```text
projects
  id               TEXT PRIMARY KEY
  name             TEXT            -- display name; safeFileName() used for exports
  audio_path       TEXT            -- original audio file location
  duration_sec     REAL            -- actual media duration (fractional)
  language         TEXT NULL       -- BCP-47 from engine, or null
  created_at       INTEGER
  updated_at       INTEGER

project_words              -- raw transcription (immutable; never rewritten)
  project_id       TEXT FK
  word_index       INTEGER         -- 0-based, chronological
  text             TEXT
  start_sec        REAL            -- internal precise timing (never displayed)
  end_sec          REAL
  confidence       REAL NULL

project_buckets            -- derived; rebuilt on reprocess
  project_id       TEXT FK
  second           INTEGER         -- 0-based; display via TimestampFormat.mmss()
  status           TEXT            -- SPEECH | SILENCE | INAUDIBLE
  source_word_indexes TEXT         -- JSON array of word_index, ascending

project_corrections        -- manual edits; originals stay in project_words
  project_id       TEXT FK
  second           INTEGER
  corrected_text   TEXT
  edited_at        INTEGER

project_validation         -- latest PipelineValidator report
  project_id       TEXT FK
  overall          TEXT            -- PASS | WARNING | FAIL
  checks_json      TEXT            -- JSON array of {name, status, detail}
  computed_at      INTEGER

project_exports            -- export history
  project_id       TEXT FK
  format           TEXT            -- txt | md | json | srt
  file_path        TEXT
  exported_at      INTEGER
```

**Stage-02 extension points (explicitly out of scope for Stage 01):**
`SecondBucket.extras` (`Map<String, String>`, always empty in Stage 01) and the
JSON exporter's per-second `extras` object are reserved for beat id,
characters, locations, objects, action, emotion, visual beat, camera, image
prompt, and negative prompt. The Room schema can gain a `project_beats`
table later keyed by `(project_id, second)`. Do not implement these now.
