#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BIN="${TMPDIR:-/tmp}/minefade-world-smoke-tests"
RUST_TARGET_DIR="${TMPDIR:-/tmp}/minefade-rust-target"
RUST_LIB="$RUST_TARGET_DIR/release/libminefade_core.a"

command -v cargo >/dev/null 2>&1 || {
  echo "Rust stable toolchain (cargo) is required for Minefade core tests." >&2
  exit 1
}

echo "[1/3] Running Rust simulation and animation unit tests"
CARGO_TARGET_DIR="$RUST_TARGET_DIR" cargo test --manifest-path "$ROOT/rust-core/Cargo.toml"

echo "[2/3] Building Rust core static library for C++ integration tests"
CARGO_TARGET_DIR="$RUST_TARGET_DIR" cargo build --manifest-path "$ROOT/rust-core/Cargo.toml" --release
test -s "$RUST_LIB"

echo "[3/3] Running native C++ world, player and catalog regression tests"
c++ -std=c++20 -O2 -Wall -Wextra -Wpedantic -Wconversion -Wshadow \
  -I "$ROOT/rust-core/include" \
  "$ROOT/tests/WorldSmokeTests.cpp" \
  "$ROOT/app/src/main/cpp/world/VoxelWorld.cpp" \
  "$ROOT/app/src/main/cpp/blocks/BlockRegistry.cpp" \
  "$ROOT/app/src/main/cpp/game/Player.cpp" \
  "$RUST_LIB" -ldl -lpthread -lm \
  -o "$BIN"
"$BIN"
rm -f "$BIN"

echo "All Minefade Rust/C++ regression tests passed."
