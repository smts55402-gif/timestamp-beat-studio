"""E2E transcription for Timestamp Beat Studio Stage-01 verification.

Transcribes the real 838.75s narration audio with word-level timestamps and
writes a words JSON that the :core E2E harness consumes.
"""
import json
from faster_whisper import WhisperModel

AUDIO = "/home/hatch/workspace/user/files/15_Goldusalexis_3_d0nz.m4a"
OUT = "/home/hatch/workspace/timestamp-beat-studio/e2e/words.json"

MODEL_DIR = (
    "/home/hatch/.cache/huggingface/hub/models--Systran--faster-whisper-small"
    "/snapshots/536b0662742c02347bc0e980a01041f333bce120"
)

# Local snapshot is used directly (no Hub download): avoids the venv's old
# httpx choking on the environment's proxy variables.
model = WhisperModel(MODEL_DIR, device="cpu", compute_type="int8")
segments, info = model.transcribe(
    AUDIO,
    language="en",
    beam_size=5,
    word_timestamps=True,
    vad_filter=True,
    vad_parameters=dict(min_silence_duration_ms=400),
)

words = []
for s in segments:
    for w in (s.words or []):
        words.append({
            "text": w.word.strip(),
            "start": round(w.start, 3),
            "end": round(w.end, 3),
            "probability": round(w.probability, 4),
        })

# Drop empty tokens that slipped through stripping.
words = [w for w in words if w["text"]]

with open(OUT, "w") as f:
    json.dump({
        "audio": AUDIO,
        "duration": info.duration,
        "language": info.language,
        "word_count": len(words),
        "words": words,
    }, f)

print(f"duration={info.duration:.3f}s language={info.language} words={len(words)}")
