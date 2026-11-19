#!/usr/bin/env python3
"""Source-level guard for reusable per-chunk GPU buffer storage."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "app/src/main/cpp"
HEADER = (CPP / "render/WorldMeshRenderer.h").read_text(encoding="utf-8")
RENDERER = (CPP / "render/WorldMeshRenderer.cpp").read_text(encoding="utf-8")

assert "GLsizeiptr opaqueCapacityBytes{0};" in HEADER
assert "GLsizeiptr transparentCapacityBytes{0};" in HEADER
assert "if (uploadBytes > capacityBytes)" in RENDERER
assert "glBufferData(GL_ARRAY_BUFFER, uploadBytes, nullptr, GL_DYNAMIC_DRAW);" in RENDERER
assert "glBufferSubData(GL_ARRAY_BUFFER, 0, uploadBytes, source.data() + first);" in RENDERER
assert "capacityBytes = 0;" in RENDERER
assert "gpu.opaqueCapacityBytes" in RENDERER
assert "gpu.transparentCapacityBytes" in RENDERER

print("Minefade GPU buffer reuse contract passed.")
