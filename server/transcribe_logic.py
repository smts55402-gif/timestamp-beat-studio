"""Pure transcription post-processing for the Timestamp Beat Studio server.

Model-free by design: every function here works on plain data (duck-typed
segment/word objects or dicts), so it is unit-testable without downloading
or loading any speech model. ``app.py`` feeds real faster-whisper output
into :func:`build_response`; tests feed fabricated faster-whisper lookalikes.

Never invents words: every emitted word originates from the input segments.
"""

from __future__ import annotations

import math
from typing import Any, Iterable, Optional

#: Segments whose ``avg_logprob`` is below this threshold are reported as
#: inaudible. A log-probability of -1.0 ~= 37% mean token probability, a
#: conservative cut-off for "speech exists but cannot be reliably recognized".
INAUDIBLE_LOGBROB_THRESHOLD = -1.0


def map_confidence(
    avg_logprob: Optional[float],
    word_probability: Optional[float],
) -> Optional[float]:
    """Map a confidence signal into the ``0..1`` range.

    Prefers the word-level probability when present. Otherwise maps the
    segment ``avg_logprob`` via ``exp(avg_logprob)`` — the geometric mean of
    the token probabilities, a principled ``0..1`` mapping for log-probs.
    Returns ``None`` when no usable signal is available.
    """
    if word_probability is not None:
        try:
            prob = float(word_probability)
        except (TypeError, ValueError):
            return None
        if not math.isfinite(prob):
            return None
        return min(1.0, max(0.0, prob))
    if avg_logprob is None:
        return None
    try:
        logprob = float(avg_logprob)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(logprob):
        return None
    return min(1.0, max(0.0, math.exp(logprob)))


def segment_whole_second_range(start: float, end: float) -> Optional[tuple[int, int]]:
    """Whole-second range (inclusive) intersected by the ``[start, end)`` span.

    Example: ``(2.3, 5.7)`` -> ``(2, 5)``. Returns ``None`` for empty spans.
    """
    first = math.floor(start)
    last = math.ceil(end) - 1
    if last < first or last < 0:
        return None
    return (max(0, first), last)


def merge_ranges(ranges: Iterable[Iterable[int]]) -> list[list[int]]:
    """Dedupe and merge inclusive ``[start, end]`` whole-second ranges.

    Overlapping *and* adjacent ranges merge (``[1, 2]`` + ``[3, 4]`` -> ``[1, 4]``),
    because adjacent inaudible seconds form one continuous span. Output is
    sorted by start second.
    """
    normalized: list[list[int]] = []
    for item in ranges:
        start, end = int(item[0]), int(item[1])
        if end < start:
            start, end = end, start
        if end < 0:
            continue
        normalized.append([max(0, start), end])
    normalized.sort(key=lambda pair: (pair[0], pair[1]))
    merged: list[list[int]] = []
    for start, end in normalized:
        if merged and start <= merged[-1][1] + 1:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return merged


_WORD_SCHEMA_KEYS = ("text", "start", "end", "confidence")


def validate_word_entry(entry: dict[str, Any]) -> dict[str, Any]:
    """Validate one word dict against the API schema; return a normalized copy.

    Raises :class:`ValueError` on any schema violation (missing key, wrong
    type, empty text, negative or non-finite timing, ``end < start``,
    confidence outside ``0..1``).
    """
    if not isinstance(entry, dict):
        raise ValueError(f"word entry must be a dict, got {type(entry).__name__}")
    for key in _WORD_SCHEMA_KEYS:
        if key not in entry:
            raise ValueError(f"word entry missing required key: {key!r}")
    text = entry["text"]
    if not isinstance(text, str) or not text.strip():
        raise ValueError("word 'text' must be a non-empty string")
    try:
        start = float(entry["start"])
        end = float(entry["end"])
    except (TypeError, ValueError) as exc:
        raise ValueError(f"word 'start'/'end' must be numbers: {exc}") from exc
    if not math.isfinite(start) or not math.isfinite(end):
        raise ValueError("word 'start'/'end' must be finite numbers")
    if start < 0 or end < 0:
        raise ValueError("word 'start'/'end' must be >= 0")
    if end < start:
        raise ValueError(f"word 'end' ({end}) must be >= 'start' ({start})")
    confidence = entry["confidence"]
    if confidence is not None:
        try:
            confidence = float(confidence)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"word 'confidence' must be a number or null: {exc}") from exc
        if not math.isfinite(confidence) or not 0.0 <= confidence <= 1.0:
            raise ValueError("word 'confidence' must be within 0..1 or null")
    return {
        "text": text.strip(),
        "start": start,
        "end": end,
        "confidence": confidence,
    }


def _segment_words(segment: Any) -> list[dict[str, Any]]:
    """Extract normalized word dicts from one faster-whisper-like segment."""
    avg_logprob = getattr(segment, "avg_logprob", None)
    words: list[dict[str, Any]] = []
    for word in getattr(segment, "words", None) or []:
        text = getattr(word, "word", None)
        if text is None:
            text = getattr(word, "text", "")
        text = str(text).strip()
        if not text:
            continue  # never emit empty tokens; never invent replacements
        words.append(
            {
                "text": text,
                "start": float(getattr(word, "start", 0.0) or 0.0),
                "end": float(getattr(word, "end", 0.0) or 0.0),
                "confidence": map_confidence(avg_logprob, getattr(word, "probability", None)),
            }
        )
    return words


def build_response(segments: Iterable[Any], info: Any) -> dict[str, Any]:
    """Build the ``POST /transcribe`` response from faster-whisper output.

    ``segments``: iterable of faster-whisper ``Segment`` objects (duck-typed:
    ``.words`` with ``.word``/``.start``/``.end``/``.probability``,
    ``.avg_logprob``, ``.start``, ``.end``).
    ``info``: faster-whisper ``TranscriptionInfo`` (duck-typed: ``.language``,
    ``.duration``).

    Returns ``{"words": [...], "language": ..., "duration": ...,
    "inaudible_ranges": [[s, e], ...]}`` with words strictly chronological by
    ``start`` (stable sort) and inaudible ranges merged/deduped.
    """
    words: list[dict[str, Any]] = []
    inaudible: list[list[int]] = []
    for segment in segments:
        words.extend(_segment_words(segment))
        avg_logprob = getattr(segment, "avg_logprob", None)
        if avg_logprob is not None:
            try:
                logprob = float(avg_logprob)
            except (TypeError, ValueError):
                logprob = 0.0
            if logprob < INAUDIBLE_LOGBROB_THRESHOLD:
                seg_start = float(getattr(segment, "start", 0.0) or 0.0)
                seg_end = float(getattr(segment, "end", seg_start) or seg_start)
                whole = segment_whole_second_range(seg_start, seg_end)
                if whole is not None:
                    inaudible.append([whole[0], whole[1]])
    words.sort(key=lambda item: item["start"])  # stable: chronological by start
    validated = [validate_word_entry(item) for item in words]

    language = getattr(info, "language", None)
    duration = getattr(info, "duration", None)
    return {
        "words": validated,
        "language": str(language) if language else None,
        "duration": float(duration) if duration is not None else None,
        "inaudible_ranges": merge_ranges(inaudible),
    }
