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
block_count = len(source_keys)
assert len(blocks) == block_count == catalog["count"]
assert block_count >= 306
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
        assert 0 <= tile < 32 * catalog["atlas"]["rows"]
        assert tile not in all_tiles
        all_tiles.add(tile)

assert len(all_tiles) == block_count * 3
atlas_meta = catalog["atlas"]
rgba = (ROOT / "app/src/main/assets/textures/block_atlas.rgba").read_bytes()
assert len(rgba) == atlas_meta["width"] * atlas_meta["height"] * 4
expected_height = ((block_count * 3 + 31) // 32) * 18
assert atlas_meta["width"] == 576
assert atlas_meta["height"] == expected_height
assert atlas_meta["rows"] == expected_height // 18
assert len(rgba) == 576 * expected_height * 4
png = (ROOT / "docs/block_atlas_preview.png").read_bytes()
assert png[:8] == b"\x89PNG\r\n\x1a\n"
assert struct.unpack(">II", png[16:24]) == (576, expected_height)

# Modern mineral additions are appended after the original 284 IDs so saved
# worlds keep their historical block indices.
modern_keys = {
    "copper_ore", "deepslate", "deepslate_coal_ore", "deepslate_iron_ore",
    "deepslate_copper_ore", "deepslate_gold_ore", "deepslate_redstone_ore",
    "deepslate_emerald_ore", "deepslate_lapis_ore", "deepslate_diamond_ore",
    "ancient_debris", "netherite_block", "amethyst_block", "budding_amethyst",
    "amethyst_cluster", "calcite", "tuff", "basalt", "raw_iron_block",
    "raw_gold_block", "raw_copper_block", "copper_block"
}
assert modern_keys.issubset(set(source_keys))
assert modern_keys.issubset(set(by_key))

# Each mineral uses three distinct, non-empty pixel-art faces in the atlas.
def read_tile(tile_id: int) -> bytes:
    x0 = (tile_id % 32) * 18 + 1
    y0 = (tile_id // 32) * 18 + 1
    return b"".join(
        rgba[((y0 + y) * atlas_meta["width"] + x0) * 4:
             ((y0 + y) * atlas_meta["width"] + x0 + 16) * 4]
        for y in range(16)
    )

for mineral_key in (
    "coal_ore", "iron_ore", "gold_ore", "redstone_ore", "diamond_ore",
    "lapis_ore", "emerald_ore", "quartz_ore", "copper_ore",
    "deepslate_diamond_ore", "deepslate_redstone_ore", "ancient_debris",
    "amethyst_block", "amethyst_cluster"
):
    definition = by_key[mineral_key]
    face_tiles = [read_tile(definition["atlas"][face])
                  for face in ("top", "side", "bottom")]
    assert len(set(face_tiles)) == 3
    assert all(len(set(tile[i:i + 4] for i in range(0, len(tile), 4))) > 8
               for tile in face_tiles)

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
