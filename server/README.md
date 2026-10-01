# Timestamp Beat Studio — Transcription Service (`server/`)

Optional **remote** processing backend for Timestamp Beat Studio (Stage 01).
Audio bytes in, word-level timings out. The Android app's
`RemoteTranscriptionEngine` POSTs audio here, then bucketizes the returned
words into fixed 1-second buckets **client-side** (the deterministic
word→bucket rule and all validation live in the app per `docs/CORE_API.md`).

- Engine: [faster-whisper](https://github.com/SYSTRAN/faster-whisper) with
  `word_timestamps=True`. Words come only from the model — the service never
  invents, rewrites, or completes narration.
- Internal timings keep full float precision; the visible `MM:SS` formatting
  is the app's job.

## Run with pip

```bash
cd server
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000
```

## Run with Docker

```bash
cd server
docker build -t tbs-transcriber .
docker run --rm -p 8000:8000 -e WHISPER_MODEL=small tbs-transcriber
```

Or with Compose:

```bash
cd server
WHISPER_MODEL=small docker compose up --build
```

## Environment variables

| Variable            | Default | Purpose                                            |
|---------------------|---------|----------------------------------------------------|
| `WHISPER_MODEL`     | `small` | Default faster-whisper model (per-request `model` field can override; each requested model is lazy-loaded once) |
| `MAX_UPLOAD_BYTES`  | `524288000` (500 MB) | Upload size guard; larger uploads get HTTP 413 |

Device selection is automatic: `cuda` when torch reports a usable GPU,
otherwise `cpu` (`float16` on CUDA, `int8` on CPU).

## API

### `GET /health`

```bash
curl http://localhost:8000/health
```

```json
{"status": "ok", "engine": "faster-whisper", "version": "1.0.0"}
```

### `POST /transcribe`

Multipart form fields:

| Field      | Required | Default | Description                              |
|------------|----------|---------|------------------------------------------|
| `file`     | yes      | —       | Audio bytes                              |
| `model`    | no       | `small` | faster-whisper model id (e.g. `tiny`, `base`, `small`, `medium`, `large-v3`, `turbo`) |
| `language` | no       | auto    | Language code, e.g. `en`; omit to auto-detect |

```bash
curl -X POST http://localhost:8000/transcribe \
  -F "file=@voice_note.m4a;type=audio/mp4" \
  -F "model=small" \
  -F "language=en"
```

Response:

```json
{
  "words": [
    {"text": "You're", "start": 0.42, "end": 0.71, "confidence": 0.93},
    {"text": "standing", "start": 0.72, "end": 1.10, "confidence": 0.88}
  ],
  "language": "en",
  "duration": 838.75,
  "inaudible_ranges": [[412, 415], [700, 701]]
}
```

- `words`: strictly chronological by `start`. `start`/`end` are float seconds
  (internal precision — the app maps these to whole-second buckets).
- `confidence`: word-level probability when available, else `exp(avg_logprob)`
  mapped to `0..1`; `null` when no signal exists.
- `inaudible_ranges`: inclusive whole-second ranges where a segment's
  `avg_logprob < -1.0` (speech exists but is unreliable). Merged and deduped.
  The app renders these seconds as `[INAUDIBLE]`; seconds with no words at all
  are `[SILENCE]`.

Errors:

| Status | Meaning |
|--------|---------|
| 400 | Missing/empty `file`, unsupported media type, or invalid `model` name |
| 413 | Upload exceeds `MAX_UPLOAD_BYTES` |
| 500 | Engine failure — body is `{"detail": "..."}` |

## Android emulator note

From an app running in the Android emulator, the host machine is **not**
`localhost`. Point `RemoteTranscriptionEngine` at:

```
http://10.0.2.2:8000
```

(On a physical device on the same LAN, use the host's LAN IP instead.)

## Security notes

- **CORS is wide open** (`allow_origins=["*"]`) as a dev default so the app
  and emulators work without preflight friction. Tighten it before any public
  exposure.
- No secrets are required; never commit API keys or credentials. Model files
  download from Hugging Face on first use per model id.

## Tests

No model download needed — pure logic plus route-level error paths:

```bash
cd server
pip install pytest httpx   # plus -r requirements.txt for the app import
python -m pytest tests/ -v
```
