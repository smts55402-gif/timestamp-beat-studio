"""Chunked E2E transcription for the real 13:58 narration audio.

Splits the audio into 3-minute chunks (low memory footprint per chunk),
transcribes each with faster-whisper `small` (word-level timestamps),
offsets word times by the chunk start, merges, and writes e2e/words.json.

Usage: python transcribe_e2e_chunked.py   (run from the e2e/ directory)
"""

import json
import os
import subprocess
import sys
import tempfile

from faster_whisper import WhisperModel

AUDIO = "/home/hatch/workspace/user/files/15_Goldusalexis_3_d0nz.m4a"
CHUNK_SEC = 180  # 3-minute chunks -> small memory footprint

MODEL_DIR = (
    "/home/hatch/.cache/huggingface/hub/models--Systran--faster-whisper-small"
    "/snapshots/536b0662742c02347bc0e980a01041f333bce120"
)


def sh(*args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} failed:\n{r.stderr[-2000:]}")
    return r


def probe_duration(path):
    r = sh(
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", path,
    )
    return float(r.stdout.strip())


def main():
    duration = probe_duration(AUDIO)
    print(f"audio duration: {duration:.3f}s", flush=True)

    model = WhisperModel(MODEL_DIR, device="cpu", compute_type="int8")
    print("model loaded", flush=True)

    tmpdir = tempfile.mkdtemp(prefix="e2e_chunks_")
    all_words = []
    detected_langs = {}
    n_chunks = int((duration + CHUNK_SEC - 1) // CHUNK_SEC)

    for ci in range(n_chunks):
        start = ci * CHUNK_SEC
        length = min(CHUNK_SEC, duration - start)
        chunk_path = os.path.join(tmpdir, f"chunk_{ci:03d}.wav")
        sh(
            "ffmpeg", "-v", "error", "-y",
            "-ss", f"{start:.3f}", "-t", f"{length:.3f}",
            "-i", AUDIO, "-ar", "16000", "-ac", "1", chunk_path,
        )

        segments, info = model.transcribe(
            chunk_path,
            language="en",
            beam_size=5,
            word_timestamps=True,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=400),
        )
        detected_langs[info.language] = (
            detected_langs.get(info.language, 0) + info.language_probability
        )

        n_seg = 0
        n_w = 0
        for seg in segments:
            n_seg += 1
            for w in (seg.words or []):
                text = w.word.strip()
                if not text:
                    continue
                all_words.append({
                    "text": text,
                    "start": round(start + w.start, 3),
                    "end": round(start + w.end, 3),
                    "probability": round(float(w.probability), 4),
                })
                n_w += 1
        print(
            f"chunk {ci + 1}/{n_chunks} [{start:.0f}s-{start + length:.0f}s]: "
            f"{n_seg} segments, {n_w} words (total {len(all_words)})",
            flush=True,
        )
        os.remove(chunk_path)

    all_words.sort(key=lambda w: w["start"])
    language = max(detected_langs, key=detected_langs.get) if detected_langs else None

    out = {
        "audio": os.path.basename(AUDIO),
        "duration": round(duration, 3),
        "language": language,
        "word_count": len(all_words),
        "chunks": n_chunks,
        "chunk_sec": CHUNK_SEC,
        "words": all_words,
    }
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "words.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(f"wrote {out_path}: {len(all_words)} words, {duration:.3f}s", flush=True)


if __name__ == "__main__":
    sys.exit(main())
