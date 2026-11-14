#!/usr/bin/env python3
"""Generate deterministic, original PCM sound cues for Minefade."""
from __future__ import annotations
import math
import random
import struct
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "audio"
RATE = 22050

# Each cue is synthesized from short oscillators and shaped noise. These are
# original game sound effects, not recordings of copyrighted game audio.
CUES = {
    "block_wood": (0.24, 210.0, 0.42, 0.14, 0.28),
    "block_stone": (0.18, 118.0, 0.62, 0.06, 0.36),
    "block_dirt": (0.22, 86.0, 0.26, 0.50, 0.22),
    "block_leaves": (0.30, 580.0, 0.08, 0.88, 0.24),
    "sheep": (0.48, 430.0, 0.48, 0.18, 0.22),
    "cow": (0.62, 150.0, 0.72, 0.10, 0.20),
    "bird": (0.28, 1550.0, 0.38, 0.04, 0.26),
    "pig": (0.34, 330.0, 0.50, 0.22, 0.24),
    "zombie": (0.72, 115.0, 0.50, 0.46, 0.22),
    "skeleton": (0.44, 560.0, 0.22, 0.68, 0.18),
    "rabbit": (0.19, 720.0, 0.24, 0.16, 0.22),
    "chest_open": (0.38, 175.0, 0.50, 0.24, 0.22),
    "command_success": (0.20, 880.0, 0.35, 0.0, 0.18),
    "water": (0.55, 310.0, 0.15, 0.78, 0.14),
}


def synth(name: str, duration: float, base: float, tone: float,
          noise: float, gain: float) -> bytes:
    seed = int.from_bytes(name.encode("utf-8"), "little", signed=False) & 0xFFFFFFFF
    rng = random.Random(seed)
    total = max(1, int(RATE * duration))
    data = bytearray()
    phase = 0.0
    phase2 = 0.0
    for index in range(total):
        t = index / RATE
        progress = index / max(1, total - 1)
        attack = min(1.0, t / 0.012)
        release = min(1.0, max(0.0, (duration - t) / max(0.045, duration * 0.22)))
        envelope = attack * release
        wobble = math.sin(2.0 * math.pi * (1.3 + base / 210.0) * t)
        sweep = base * (1.0 + 0.14 * math.sin(math.pi * progress) + 0.035 * wobble)
        phase += 2.0 * math.pi * sweep / RATE
        phase2 += 2.0 * math.pi * sweep * 1.503 / RATE
        fundamental = math.sin(phase)
        harmonic = math.sin(phase2) * 0.32
        sub = math.sin(phase * 0.5) * 0.18
        rattle = (rng.random() * 2.0 - 1.0)
        pulse = math.sin(math.pi * min(1.0, progress * 1.8)) if name in {"bird", "command_success"} else 1.0
        sample = (tone * (fundamental + harmonic + sub) +
                  noise * rattle * (0.6 + 0.4 * (1.0 - progress)) +
                  0.06 * wobble) * envelope * pulse * gain
        sample = max(-0.92, min(0.92, sample))
        data.extend(struct.pack("<h", int(sample * 32767)))
    return bytes(data)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, params in CUES.items():
        pcm = synth(name, *params)
        path = OUT / f"{name}.wav"
        with wave.open(str(path), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(RATE)
            output.writeframes(pcm)
        if path.stat().st_size < 2048:
            raise RuntimeError(f"Generated audio cue is unexpectedly small: {path}")
    print(f"Generated {len(CUES)} original PCM sound cues at {RATE} Hz.")
    print(f"Output: {OUT}")


if __name__ == "__main__":
    main()
