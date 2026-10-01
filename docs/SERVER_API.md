# Server API — Remote Transcription Engine (`server/`)

FastAPI + faster-whisper service implementing the remote
`TranscriptionEngine`. It returns the same `TranscriptionResult` shape as the
on-device whisper.cpp engine so `:core` bucketing, validation, and export
behave identically regardless of which engine produced the transcript.

Base URL (docker compose default): `http://localhost:8000`

## GET /health

Liveness/readiness probe.

**Response `200`:**

```json
{
  "status": "ok",
  "engine": "faster-whisper",
  "model": "small",
  "device": "cpu"
}
```

## POST /transcribe

Transcribe an audio file into words with internal precise timing.

**Request:** `multipart/form-data`

| Field | Type | Required | Notes |
|---|---|---|---|
| `file` | binary | yes | Audio file (m4a, mp3, wav, ogg, …). Server decodes and resamples to 16 kHz mono. |
| `language` | string | no | BCP-47 hint (e.g. `en`). Omit for auto-detect. |

**Response `200`** — mirrors `TranscriptionResult`:

```json
{
  "words": [
    { "text": "You're", "start": 0.41, "end": 0.72, "confidence": 0.98 },
    { "text": "standing", "start": 0.73, "end": 1.10, "confidence": 0.97 }
  ],
  "language": "en",
  "duration": 838.75,
  "inaudible_ranges": [[512, 514]]
}
```

- `words` — chronological by `start`; fractional seconds; never split across
  the wire (the client bucketizer enforces the no-split rule).
- `language` — BCP-47 detected by the engine, or `null` if unknown.
- `duration` — actual media duration as decoded.
- `inaudible_ranges` — inclusive whole-second ranges where speech was
  detected but not reliably recognized; the client renders these as
  `[INAUDIBLE]`. Empty array when none.

**Error responses:**

| Status | Meaning | Body |
|---|---|---|
| `400` | Unsupported/empty file | `{ "detail": "unsupported audio format" }` |
| `422` | Transcription failed | `{ "detail": "<engine error>" }` |

The server never rewrites, summarizes, or completes the transcript — it
returns recognized words only. Low-confidence spans the engine cannot stand
behind are reported via `inaudible_ranges_sec`, never guessed.

Run it with `docker compose up --build` from `server/`; run its tests with
`pytest`. See `README.md`.
