#!/usr/bin/env python3
"""Validate deterministic generated PCM sound cues."""
from __future__ import annotations
import pathlib
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
AUDIO = ROOT / "app" / "src" / "main" / "assets" / "audio"
EXPECTED = {
    "block_wood", "block_stone", "block_dirt", "block_leaves",
    "sheep", "cow", "bird", "pig", "zombie", "skeleton", "rabbit",
    "chest_open", "command_success", "water",
}
for name in sorted(EXPECTED):
    path = AUDIO / f"{name}.wav"
    assert path.is_file(), f"Missing generated cue: {path}"
    with wave.open(str(path), "rb") as wav:
        assert wav.getnchannels() == 1
        assert wav.getsampwidth() == 2
        assert wav.getframerate() == 22050
        assert 0.10 <= wav.getnframes() / wav.getframerate() <= 1.0
        assert wav.getnframes() > 0
        assert len(wav.readframes(wav.getnframes())) == wav.getnframes() * 2
print(f"Minefade audio pack passed: {len(EXPECTED)} generated mono PCM cues.")
