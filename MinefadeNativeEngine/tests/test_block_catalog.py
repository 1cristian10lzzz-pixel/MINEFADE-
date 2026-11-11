#!/usr/bin/env python3
"""Validate Minefade's one-entry-per-source-block registry and atlas."""
from __future__ import annotations
import json
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parents[1]
source = json.loads((ROOT / "app/src/main/assets/data/minecraft_1_8_block_definitions.json").read_text(encoding="utf-8"))
catalog = json.loads((ROOT / "app/src/main/assets/data/minefade_block_catalog.json").read_text(encoding="utf-8"))
blocks = catalog["blocks"]
source_keys = [key for key in source if key != "format_version"]
assert len(blocks) == 284 == catalog["count"] == len(source_keys)
assert [block["key"] for block in blocks] == source_keys
assert [block["id"] for block in blocks] == list(range(284))

all_tiles: set[int] = set()
for block in blocks:
    assert block["name_es"].strip()
    assert block["shape"] in {
        "None", "Cube", "Slab", "Stairs", "CrossPlant", "Thin", "Pane",
        "Fence", "Door", "Trapdoor", "Fluid", "Scaffolding"
    }
    assert block["stack_limit"] > 0
    assert 0 <= block["drop_id"] < len(blocks)
    assert all(isinstance(block["texture_aliases"][face], str)
               for face in ("top", "side", "bottom"))
    tiles = block["atlas"]
    assert set(tiles) == {"top", "side", "bottom"}
    for tile in tiles.values():
        assert 0 <= tile < 864
        assert tile not in all_tiles
        all_tiles.add(tile)

assert len(all_tiles) == 284 * 3
atlas_meta = catalog["atlas"]
rgba = (ROOT / "app/src/main/assets/textures/block_atlas.rgba").read_bytes()
assert len(rgba) == atlas_meta["width"] * atlas_meta["height"] * 4
assert len(rgba) == 576 * 486 * 4
png = (ROOT / "docs/block_atlas_preview.png").read_bytes()
assert png[:8] == b"\x89PNG\r\n\x1a\n"
assert struct.unpack(">II", png[16:24]) == (576, 486)

# Guard the mappings that are not dropped as their currently-lit/flowing ID.
by_key = {block["key"]: block for block in blocks}
assert by_key["flowing_water"]["drop_id"] == by_key["water"]["id"]
assert by_key["flowing_lava"]["drop_id"] == by_key["lava"]["id"]
assert by_key["lit_furnace"]["drop_id"] == by_key["furnace"]["id"]
assert by_key["lit_redstone_ore"]["drop_id"] == by_key["redstone_ore"]["id"]
assert by_key["portal"]["hardness"] < 0
assert by_key["end_portal"]["hardness"] < 0
assert by_key["end_gateway"]["hardness"] < 0
print(f"Minefade block catalogue passed: {len(blocks)} blocks, {len(all_tiles)} unique face tiles, {len(rgba)} RGBA bytes.")
