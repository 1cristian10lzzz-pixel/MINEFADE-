#!/usr/bin/env python3
"""Validate Minefade's bounded, data-only .mfshader profile format v1."""
from __future__ import annotations
import argparse
import math
import re
from pathlib import Path

MAX_BYTES, MAX_LINES = 64 * 1024, 128
ALLOWED = {"format", "id", "name", "version", "water.waves", "lava.animation", "leaves.wind", "leaves.strength"}


def parse_shader(path: Path) -> dict[str, str]:
    raw = path.read_bytes()
    if not raw or len(raw) > MAX_BYTES:
        raise ValueError("tamaño fuera del límite")
    values: dict[str, str] = {}
    lines = raw.decode("utf-8").splitlines()
    if len(lines) > MAX_LINES:
        raise ValueError("demasiadas líneas")
    for number, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"línea {number}: se esperaba clave=valor")
        key, value = (part.strip() for part in line.split("=", 1))
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", key) or key not in ALLOWED:
            raise ValueError(f"línea {number}: clave no soportada")
        if key in values or len(value) > 512:
            raise ValueError(f"línea {number}: duplicada o demasiado larga")
        values[key] = value
    if values.get("format") != "1" or not re.fullmatch(r"[A-Za-z0-9_.-]{1,48}", values.get("id", "")):
        raise ValueError("manifiesto incompleto o formato incompatible")
    if not values.get("name") or not values.get("version"):
        raise ValueError("faltan name/version")
    for key in ("water.waves", "lava.animation", "leaves.wind"):
        if key in values and values[key].lower() not in {"true", "false"}:
            raise ValueError(f"{key} debe ser true/false")
    try:
        strength = float(values.get("leaves.strength", "0.04"))
    except ValueError as error:
        raise ValueError("leaves.strength debe ser numérico") from error
    if not math.isfinite(strength) or not 0.0 <= strength <= 0.12:
        raise ValueError("leaves.strength fuera de rango 0..0.12")
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packs", nargs="+", type=Path)
    args = parser.parse_args()
    failed = False
    for pack in args.packs:
        try:
            values = parse_shader(pack)
            print(f"OK {pack}: {values['id']} (formato {values['format']})")
        except (OSError, UnicodeDecodeError, ValueError) as error:
            failed = True
            print(f"ERROR {pack}: {error}")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
