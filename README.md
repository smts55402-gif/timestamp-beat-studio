# Timestamp Beat Studio

![Build & Test](https://github.com/smts55402-gif/timestamp-beat-studio/actions/workflows/build.yml/badge.svg)

**Stage 01** of the Master Production System: turn a voice/audio file into a
**complete second-by-second timestamped transcript** — one fixed one-second
record per second of audio, zero skipped words, zero duplicated words, zero
invented text.

## What it does

```
Audio file
  → speech transcription (word-level internal timing)
  → deterministic assignment into fixed 1-second buckets
  → validation (7 checks: coverage, duplicates, invention, order, format, ...)
  → second-by-second beat sheet + export (TXT / MD / JSON / SRT)
```

Scope for Stage 01 is deliberately narrow: **one second + exact spoken text**.
No semantic beat splitting, no image/character/camera prompts — those belong
to Stage 02 and are explicitly out of scope here.

## The one rule

> **ONE AUDIO SECOND = ONE TIMESTAMP RECORD.**

Every second from the start of the audio to the end gets exactly one record,
using the `MM:SS` display format. Seconds with no detected speech are kept as
`[SILENCE]`; speech that cannot be reliably recognized is marked `[INAUDIBLE]`
— never guessed.

Example output for any audio duration:

```text
00:00 You're standing at the edge of a still pond.
00:01 It's about 40,000 years ago.
00:02 Somewhere in what's now southern France.
00:03 And here's the strange part.
00:04 You have never once in your entire life seen your own face.
00:05 Not in a mirror, not in a photo, not in anything.
00:06 You've probably checked your reflection five or six times
00:07 today without even registering it.
...
13:55 who gets to actually see the answer looking back.
13:56 [SILENCE]
13:57 [SILENCE]
```

No word-level timestamps, no decimal seconds, no `START/END` pairs, no time
ranges — the visible beat sheet is always whole seconds only. Precise word
timing exists internally only to assign words to the correct bucket, and is
never displayed (except inside SRT cue timing, which is generated from it).

## Repository structure

```text
timestamp-beat-studio/
├── .github/workflows/build.yml   # CI: test → assemble debug APK → upload artifact
├── core/                         # :core  — pure Kotlin/JVM domain logic
│                                 #          (bucketizer, validator, exporters, TimestampFormat)
├── app/                          # :app   — Android app (Kotlin + Jetpack Compose + Room)
├── whisper/                      # :whisper — JNI wrapper around whisper.cpp (CMake/NDK)
├── server/                       # FastAPI + faster-whisper remote transcription engine
├── docs/
│   ├── CORE_API.md               # frozen contract consumed by :app and :whisper
│   ├── ARCHITECTURE.md           # modules, data flow, Room schema, Stage-02 extension points
│   ├── PIPELINE.md               # deterministic 1-second bucketing spec
│   ├── VALIDATION.md             # the 7 checks, WARNING vs FAIL, coverage-error gate
│   ├── EXPORT_FORMATS.md         # TXT/MD/JSON/SRT specs and filename convention
│   └── SERVER_API.md             # /health and /transcribe contract
├── gradle/                       # version catalog (libs.versions.toml)
└── settings.gradle.kts
```

## Tech stack

| Layer | Technology |
|---|---|
| App | Kotlin, Jetpack Compose (Material 3), Room, MediaPlayer |
| Domain | Pure Kotlin/JVM (`:core`) — no Android dependencies |
| On-device STT | whisper.cpp via JNI (`:whisper`, CMake + NDK) |
| Remote STT | FastAPI server (`server/`) + faster-whisper |
| Build | Gradle 8 / AGP 8.5, JDK 17, Android SDK 34, NDK 26 |
| CI | GitHub Actions (`Build & Test`) |

The domain contract is frozen and documented in [`docs/CORE_API.md`](docs/CORE_API.md):
`SecondBucketizer` (word → bucket rule), `PipelineValidator` (7 checks),
`Exporters` (TXT/MD/JSON/SRT), and `TimestampFormat` (`MM:SS` only).

## How to build

Prerequisites: **JDK 17** and the **Android SDK** (platform 34, build-tools
34.0.0, NDK 26.3.11579264, CMake 3.22.1 — installed automatically by CI).

> **Note:** `gradle/wrapper/gradle-wrapper.jar` is stored in the repo as
> `gradle-wrapper.jar.b64` (base64). Restore it once after cloning:
> ```bash
> base64 -d gradle/wrapper/gradle-wrapper.jar.b64 > gradle/wrapper/gradle-wrapper.jar
> ```
> (CI does this automatically before validating the wrapper.)

```bash
# Debug APK (also compiles :whisper native code via CMake/NDK)
./gradlew :app:assembleDebug

# Output:
# app/build/outputs/apk/debug/app-debug.apk
```

## How to run tests

```bash
# Domain unit tests (bucketing, validation, exports, timestamp format)
./gradlew :core:test

# Android unit tests (debug)
./gradlew :app:testDebugUnitTest

# Server tests
cd server && pytest
```

## How to run the server (remote transcription engine)

```bash
cd server
docker compose up --build
# Health check:  GET http://localhost:8000/health
# Transcribe:    POST http://localhost:8000/transcribe  (multipart audio file)
```

The app can use either the on-device whisper.cpp engine or this remote
faster-whisper engine. Both feed the same `:core` bucketing and validation
pipeline, so results are identical in structure.

## Validation and honesty

The app never claims accuracy it hasn't measured. Every run produces a
validation report with `PASS` / `WARNING` / `FAIL` per check. If coverage or
duplication checks fail, the UI shows **COVERAGE ERROR — REVIEW REQUIRED** and
refuses a PASS badge. Claims like "100% accurate" are not used anywhere —
status is demonstrated by validation, not asserted.

See [`docs/VALIDATION.md`](docs/VALIDATION.md) for the exact check definitions.

## License

MIT — see [LICENSE](LICENSE).
