#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BIN="${TMPDIR:-/tmp}/minefade-world-smoke-tests"
c++ -std=c++20 -O2 -Wall -Wextra -Wpedantic -Wconversion -Wshadow \
  "$ROOT/tests/WorldSmokeTests.cpp" \
  "$ROOT/app/src/main/cpp/world/VoxelWorld.cpp" \
  "$ROOT/app/src/main/cpp/blocks/BlockRegistry.cpp" \
  "$ROOT/app/src/main/cpp/game/Player.cpp" \
  -o "$BIN"
"$BIN"
rm -f "$BIN"
python3 "$ROOT/tests/test_block_catalog.py"
