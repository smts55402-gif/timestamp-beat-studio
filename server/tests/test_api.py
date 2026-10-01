"""Tests for the transcription service.

No model is downloaded: route tests exercise /health and the pre-engine
error paths (400/413), while transcription logic is tested through the
pure, model-free functions in transcribe_logic using fabricated
faster-whisper-like segment objects.
"""

import math
import os
import sys

import pytest
from fastapi.testclient import TestClient
from types import SimpleNamespace

sys.path.insert(
    0, os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)

import app as server_app  # noqa: E402
import transcribe_logic as tl  # noqa: E402

client = TestClient(server_app.app)


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------
def test_health_returns_200_with_engine_info():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["engine"] == "faster-whisper"
    assert isinstance(body["version"], str) and body["version"]


# ---------------------------------------------------------------------------
# /transcribe error paths (all before any model load)
# ---------------------------------------------------------------------------
def test_transcribe_missing_file_returns_400():
    response = client.post("/transcribe")
    assert response.status_code == 400
    assert "detail" in response.json()


def test_transcribe_empty_file_returns_400():
    response = client.post(
        "/transcribe",
        files={"file": ("empty.wav", b"", "audio/wav")},
    )
    assert response.status_code == 400


def test_transcribe_unsupported_media_type_returns_400():
    response = client.post(
        "/transcribe",
        files={"file": ("photo.png", b"\x89PNG-not-audio", "image/png")},
    )
    assert response.status_code == 400


def test_transcribe_oversize_file_returns_413(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_BYTES", "10")
    response = client.post(
        "/transcribe",
        files={"file": ("audio.wav", b"x" * 100, "audio/wav")},
    )
    assert response.status_code == 413


def test_transcribe_invalid_model_name_returns_400():
    response = client.post(
        "/transcribe",
        files={"file": ("audio.wav", b"fake-bytes", "audio/wav")},
        data={"model": "../../etc/passwd"},
    )
    assert response.status_code == 400


# ---------------------------------------------------------------------------
# Helpers: fabricated faster-whisper-like objects (duck-typed)
# ---------------------------------------------------------------------------
def _fake_transcription():
    seg1 = SimpleNamespace(
        start=0.0,
        end=2.5,
        avg_logprob=-0.2,
        words=[
            SimpleNamespace(word="Hello", start=0.10, end=0.50, probability=0.95),
            SimpleNamespace(word="world", start=0.60, end=1.00, probability=0.90),
        ],
    )
    seg2 = SimpleNamespace(
        start=2.5,
        end=4.2,
        avg_logprob=-2.5,  # below threshold -> inaudible
        words=[
            SimpleNamespace(word="mumble", start=2.60, end=3.40, probability=None),
        ],
    )
    info = SimpleNamespace(language="en", duration=4.2)
    return [seg1, seg2], info


# ---------------------------------------------------------------------------
# build_response: word schema, ordering, confidence, inaudible ranges
# ---------------------------------------------------------------------------
def test_build_response_word_schema_and_order():
    segments, info = _fake_transcription()
    response = tl.build_response(segments, info)

    assert set(response.keys()) == {"words", "language", "duration", "inaudible_ranges"}
    assert response["language"] == "en"
    assert response["duration"] == pytest.approx(4.2)

    words = response["words"]
    assert [w["text"] for w in words] == ["Hello", "world", "mumble"]
    for word in words:
        assert isinstance(word["text"], str) and word["text"]
        assert isinstance(word["start"], float)
        assert isinstance(word["end"], float)
        assert 0.0 <= word["start"] <= word["end"]
        assert word["confidence"] is None or 0.0 <= word["confidence"] <= 1.0

    starts = [w["start"] for w in words]
    assert starts == sorted(starts), "words must be strictly chronological by start"


def test_build_response_confidence_mapping():
    segments, info = _fake_transcription()
    words = tl.build_response(segments, info)["words"]
    # word-level probability wins when present
    assert words[0]["confidence"] == pytest.approx(0.95)
    assert words[1]["confidence"] == pytest.approx(0.90)
    # otherwise avg_logprob is mapped via exp() into 0..1
    assert words[2]["confidence"] == pytest.approx(math.exp(-2.5))


def test_build_response_confidence_null_when_no_signal():
    segments = [
        SimpleNamespace(
            start=0.0,
            end=1.0,
            avg_logprob=None,
            words=[SimpleNamespace(word="hi", start=0.1, end=0.5, probability=None)],
        )
    ]
    words = tl.build_response(segments, SimpleNamespace(language=None, duration=1.0))["words"]
    assert words[0]["confidence"] is None


def test_build_response_sorts_out_of_order_words():
    segments = [
        SimpleNamespace(
            start=0.0,
            end=2.0,
            avg_logprob=-0.1,
            words=[
                SimpleNamespace(word="b", start=1.0, end=1.5, probability=0.8),
                SimpleNamespace(word="a", start=0.2, end=0.6, probability=0.8),
            ],
        )
    ]
    words = tl.build_response(segments, SimpleNamespace(language=None, duration=2.0))["words"]
    assert [w["text"] for w in words] == ["a", "b"]


def test_build_response_inaudible_ranges():
    segments, info = _fake_transcription()
    response = tl.build_response(segments, info)
    # seg2 [2.5, 4.2) with avg_logprob -2.5 < -1.0 -> whole seconds [2, 4]
    assert response["inaudible_ranges"] == [[2, 4]]


def test_build_response_no_inaudible_when_confident():
    segments = [
        SimpleNamespace(
            start=0.0,
            end=1.0,
            avg_logprob=-0.1,
            words=[SimpleNamespace(word="ok", start=0.0, end=0.5, probability=0.99)],
        )
    ]
    response = tl.build_response(segments, SimpleNamespace(language="en", duration=1.0))
    assert response["inaudible_ranges"] == []


# ---------------------------------------------------------------------------
# merge_ranges
# ---------------------------------------------------------------------------
def test_merge_ranges_overlapping_and_unsorted():
    # [1,3]+[2,4] overlap -> [1,4]; [1,4] is adjacent to [5,7] -> [1,7]
    assert tl.merge_ranges([[5, 7], [1, 3], [2, 4], [10, 10]]) == [[1, 7], [10, 10]]


def test_merge_ranges_adjacent_merge():
    assert tl.merge_ranges([[1, 2], [3, 4]]) == [[1, 4]]


def test_merge_ranges_dedupe_and_empty():
    assert tl.merge_ranges([[2, 3], [2, 3], [2, 3]]) == [[2, 3]]
    assert tl.merge_ranges([]) == []


def test_merge_ranges_whole_second_spans():
    assert tl.segment_whole_second_range(2.3, 5.7) == (2, 5)
    assert tl.segment_whole_second_range(0.0, 1.0) == (0, 0)
    assert tl.segment_whole_second_range(3.0, 3.0) is None


# ---------------------------------------------------------------------------
# validate_word_entry
# ---------------------------------------------------------------------------
def test_validate_word_entry_accepts_valid():
    entry = {"text": "hi", "start": 1.0, "end": 2.0, "confidence": 0.5}
    assert tl.validate_word_entry(entry) == entry


def test_validate_word_entry_rejects_bad_input():
    with pytest.raises(ValueError):
        tl.validate_word_entry({"text": "", "start": 0, "end": 1, "confidence": None})
    with pytest.raises(ValueError):
        tl.validate_word_entry({"text": "hi", "start": 2.0, "end": 1.0, "confidence": None})
    with pytest.raises(ValueError):
        tl.validate_word_entry({"text": "hi", "start": 0, "end": 1})  # missing key
    with pytest.raises(ValueError):
        tl.validate_word_entry({"text": "hi", "start": "x", "end": 1, "confidence": None})
    with pytest.raises(ValueError):
        tl.validate_word_entry({"text": "hi", "start": 0, "end": 1, "confidence": 1.5})
    with pytest.raises(ValueError):
        tl.validate_word_entry("not-a-dict")
