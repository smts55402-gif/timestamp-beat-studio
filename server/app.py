"""Timestamp Beat Studio — remote transcription service (Stage 01 backend).

Optional backend: audio bytes in, word-level timings out. The Android app's
``RemoteTranscriptionEngine`` POSTs audio here; the app then bucketizes the
returned words into fixed 1-second buckets client-side (see
``docs/CORE_API.md`` — the deterministic word->bucket rule lives in the app,
not here).

Run (dev):
    uvicorn app:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import os
import re
import tempfile
import threading
from typing import Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from starlette.concurrency import run_in_threadpool

import transcribe_logic

SERVICE_VERSION = "1.0.0"
ENGINE_NAME = "faster-whisper"

DEFAULT_MODEL = os.environ.get("WHISPER_MODEL", "small")
DEFAULT_MAX_UPLOAD_BYTES = 500 * 1024 * 1024  # 500 MB

# Model names are passed to faster-whisper as a size id or local path. Restrict
# to plain identifiers so a request cannot smuggle in a filesystem path.
_MODEL_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")

_AUDIO_EXTENSIONS = {
    ".wav", ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac",
    ".wma", ".aiff", ".aif", ".aifc", ".webm", ".mp4", ".m4b", ".mkv",
    ".mov", ".3gp", ".3g2", ".amr", ".pcm", ".raw",
}
# Extensions that are definitely not audio: reject early with a clear 400.
_NON_AUDIO_EXTENSIONS = {
    ".txt", ".pdf", ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp",
    ".json", ".xml", ".csv", ".zip", ".html", ".htm", ".doc", ".docx",
}

app = FastAPI(
    title="Timestamp Beat Studio — Transcription Service",
    version=SERVICE_VERSION,
)

# DEV DEFAULT: wide-open CORS so the Android app / emulators and local tools
# can reach the service without preflight friction. Tighten `allow_origins`
# to your own domains before exposing this publicly.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# Model management: lazy-load once per model name, thread-safe.
# ---------------------------------------------------------------------------
_models: dict[str, object] = {}
_models_lock = threading.Lock()


def _default_device() -> str:
    """'cuda' when a GPU is usable, else 'cpu' (torch is optional)."""
    try:
        import torch

        if torch.cuda.is_available():
            return "cuda"
    except Exception:
        pass
    return "cpu"


def get_model(name: str) -> object:
    """Return the cached faster-whisper model, loading it on first use."""
    with _models_lock:
        if name not in _models:
            from faster_whisper import WhisperModel  # lazy: tests never import this

            device = _default_device()
            compute_type = "float16" if device == "cuda" else "int8"
            _models[name] = WhisperModel(name, device=device, compute_type=compute_type)
        return _models[name]


def _transcribe_file(model_name: str, path: str, language: Optional[str]) -> dict:
    """Blocking transcription; runs in a threadpool via the route below."""
    model = get_model(model_name)
    segments, info = model.transcribe(path, word_timestamps=True, language=language)  # type: ignore[attr-defined]
    return transcribe_logic.build_response(segments, info)


# ---------------------------------------------------------------------------
# Upload guards
# ---------------------------------------------------------------------------
async def _read_upload_limited(upload: UploadFile, max_bytes: int) -> bytes:
    """Read the upload in chunks; 413 before buffering past the limit."""
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await upload.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise HTTPException(
                status_code=413,
                detail=f"file too large: limit is {max_bytes} bytes",
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _check_media_type(upload: UploadFile) -> None:
    """400 for clearly unsupported media types; otherwise let the engine try."""
    content_type = (upload.content_type or "").split(";")[0].strip().lower()
    filename = (upload.filename or "").lower()
    if content_type.startswith("audio/") or content_type.startswith("video/"):
        return
    if content_type.startswith("text/") or content_type.startswith("image/"):
        raise HTTPException(
            status_code=400,
            detail=f"unsupported media type: {content_type or 'unknown'}",
        )
    if content_type and content_type != "application/octet-stream":
        # e.g. application/pdf, application/json, application/zip, ...
        raise HTTPException(
            status_code=400,
            detail=f"unsupported media type: {content_type}",
        )
    # Missing or generic content type: fall back to the filename extension.
    _, dot, ext = filename.rpartition(".")
    ext = f".{ext}" if dot else ""
    if ext in _NON_AUDIO_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail=f"unsupported media type for file: {upload.filename or 'unnamed'}",
        )
    if ext and ext not in _AUDIO_EXTENSIONS:
        # Unknown extension with a generic content type: accept and let the
        # engine attempt decoding; a real decode failure surfaces as a 500
        # with a detail message (never invented words).
        return


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------
@app.get("/health")
def health() -> dict:
    return {"status": "ok", "engine": ENGINE_NAME, "version": SERVICE_VERSION}


@app.post("/transcribe")
async def transcribe_endpoint(
    file: Optional[UploadFile] = File(default=None),
    model: str = Form(default="small"),
    language: Optional[str] = Form(default=None),
) -> dict:
    """Transcribe uploaded audio; return words with precise internal timings.

    Multipart form fields:
      - ``file`` (required): audio bytes.
      - ``model`` (optional, default ``"small"``): faster-whisper model id.
      - ``language`` (optional): e.g. ``"en"``; omitted = auto-detect.
    """
    if file is None:
        raise HTTPException(
            status_code=400,
            detail="missing file: expected multipart field 'file'",
        )

    model_name = (model or "").strip() or DEFAULT_MODEL
    if not _MODEL_NAME_RE.fullmatch(model_name):
        raise HTTPException(
            status_code=400,
            detail=f"invalid model name: {model_name!r}",
        )
    lang = (language or "").strip() or None

    max_bytes = int(os.environ.get("MAX_UPLOAD_BYTES", str(DEFAULT_MAX_UPLOAD_BYTES)))
    data = await _read_upload_limited(file, max_bytes)
    if not data:
        raise HTTPException(status_code=400, detail="empty file: no audio bytes received")
    _check_media_type(file)

    suffix = os.path.splitext(file.filename or "")[1].lower() or ".tmp"
    fd, tmp_path = tempfile.mkstemp(prefix="tbs_transcribe_", suffix=suffix)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
        try:
            return await run_in_threadpool(_transcribe_file, model_name, tmp_path, lang)
        except HTTPException:
            raise
        except Exception as exc:  # engine failure -> 500 with detail; never invent words
            raise HTTPException(
                status_code=500,
                detail=f"transcription failed: {exc}",
            ) from exc
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        await file.close()
