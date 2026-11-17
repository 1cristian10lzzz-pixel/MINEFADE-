#!/usr/bin/env python3
"""Validate Minefade's one-entry-per-source-block registry and atlas."""
from __future__ import annotations
import json
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parents[1]
manifest_path = ROOT / "app/src/main/assets/data/block_modules/manifest.json"
if manifest_path.is_file():
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    source_keys = manifest["order"]
    module_names = manifest["modules"]
    merged_keys = []
    for module_name in module_names:
        module = json.loads((manifest_path.parent / module_name).read_text(encoding="utf-8"))
        merged_keys.extend(module.keys())
    assert len(merged_keys) == len(set(merged_keys)) == len(source_keys)
    assert set(merged_keys) == set(source_keys)
else:
    source = json.loads((ROOT / "app/src/main/assets/data/minecraft_1_8_block_definitions.json").read_text(encoding="utf-8"))
    source_keys = [key for key in source if key != "format_version"]

catalog = json.loads((ROOT / "app/src/main/assets/data/minefade_block_catalog.json").read_text(encoding="utf-8"))
blocks = catalog["blocks"]
synthetic_item_keys = [
    "minefade_stick",
    "minefade_wooden_pickaxe",
    "minefade_wooden_axe",
    "minefade_wooden_shovel",
    "minefade_wooden_sword",
    "minefade_wooden_hoe",
    "minefade_wooden_helmet",
    "minefade_wooden_chestplate",
    "minefade_wooden_leggings",
    "minefade_wooden_boots",
    "minefade_stone_pickaxe",
    "minefade_stone_axe",
    "minefade_stone_shovel",
    "minefade_stone_sword",
    "minefade_stone_hoe",
    "minefade_stone_helmet",
    "minefade_stone_chestplate",
    "minefade_stone_leggings",
    "minefade_stone_boots",
    "minefade_copper_pickaxe",
    "minefade_copper_axe",
    "minefade_copper_shovel",
    "minefade_copper_sword",
    "minefade_copper_hoe",
    "minefade_copper_helmet",
    "minefade_copper_chestplate",
    "minefade_copper_leggings",
    "minefade_copper_boots",
    "minefade_iron_pickaxe",
    "minefade_iron_axe",
    "minefade_iron_shovel",
    "minefade_iron_sword",
    "minefade_iron_hoe",
    "minefade_iron_helmet",
    "minefade_iron_chestplate",
    "minefade_iron_leggings",
    "minefade_iron_boots",
    "minefade_gold_pickaxe",
    "minefade_gold_axe",
    "minefade_gold_shovel",
    "minefade_gold_sword",
    "minefade_gold_hoe",
    "minefade_gold_helmet",
    "minefade_gold_chestplate",
    "minefade_gold_leggings",
    "minefade_gold_boots",
    "minefade_diamond_pickaxe",
    "minefade_diamond_axe",
    "minefade_diamond_shovel",
    "minefade_diamond_sword",
    "minefade_diamond_hoe",
    "minefade_diamond_helmet",
    "minefade_diamond_chestplate",
    "minefade_diamond_leggings",
    "minefade_diamond_boots",
    "minefade_emerald_pickaxe",
    "minefade_emerald_axe",
    "minefade_emerald_shovel",
    "minefade_emerald_sword",
    "minefade_emerald_hoe",
    "minefade_emerald_helmet",
    "minefade_emerald_chestplate",
    "minefade_emerald_leggings",
    "minefade_emerald_boots",
    "minefade_copper_ingot",
    "minefade_iron_ingot",
    "minefade_gold_ingot",
    "minefade_raw_copper",
    "minefade_raw_iron",
    "minefade_raw_gold",
    "minefade_emerald",
    "minefade_diamond",
]
all_catalog_keys = source_keys + synthetic_item_keys
block_count = len(all_catalog_keys)
assert len(blocks) == block_count == catalog["count"]
assert block_count >= 306
assert [block["key"] for block in blocks] == all_catalog_keys
assert [block["key"] for block in blocks[:len(source_keys)]] == source_keys
for item in blocks[len(source_keys):]:
    assert item["kind"] == "item"
    assert item["shape"] == "None"
    assert item["flags"] == 0
assert [block["id"] for block in blocks] == list(range(block_count))

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

# The native atlas UV helper must use the generated row count. A stale fixed
# height clips every row after the old 486px atlas when more entries are added.
registry_source = (ROOT / "app/src/main/cpp/blocks/BlockRegistry.cpp").read_text(encoding="utf-8")
assert "const float atlasHeight=static_cast<float>(rows*18u)" in registry_source
assert "GetBlockDefinitionCount()*3u" in registry_source
assert "static_cast<std::size_t>(tile)>=tileCount" in registry_source
assert "v0=(y+0.5f)/atlasHeight" in registry_source
assert "v1=(y+15.5f)/atlasHeight" in registry_source
assert "1.0f-(y+" not in registry_source
assert "/486.0f" not in registry_source

png = (ROOT / "docs/block_atlas_preview.png").read_bytes()
assert png[:8] == b"\x89PNG\r\n\x1a\n"
assert struct.unpack(">II", png[16:24]) == (576, expected_height)

by_key = {block["key"]: block for block in blocks}

# Modern mineral additions are appended after the original 284 IDs so saved
# worlds keep their historical block indices.
modern_keys = {
    "copper_ore", "deepslate", "deepslate_coal_ore", "deepslate_iron_ore",
    "deepslate_copper_ore", "deepslate_gold_ore", "deepslate_redstone_ore",
    "deepslate_emerald_ore", "deepslate_lapis_ore", "deepslate_diamond_ore",
    "ancient_debris", "netherite_block", "amethyst_block", "budding_amethyst",
    "amethyst_cluster", "calcite", "tuff", "basalt", "raw_iron_block",
    "raw_gold_block", "raw_copper_block", "copper_block",
    "cherry_log", "stripped_cherry_log", "cherry_planks", "cherry_leaves",
    "cherry_sapling", "cherry_stairs", "cherry_slab", "pink_petals",
    "mangrove_log", "stripped_mangrove_log", "mangrove_planks", "mangrove_leaves",
    "mangrove_propagule", "mangrove_roots", "muddy_mangrove_roots", "mud",
    "packed_mud", "mud_bricks", "mud_brick_stairs", "mud_brick_slab",
    "sculk", "sculk_catalyst", "sculk_sensor", "sculk_shrieker",
    "reinforced_deepslate", "blackstone", "polished_blackstone", "gilded_blackstone",
    "crying_obsidian", "crimson_planks", "warped_planks", "crimson_stairs",
    "warped_stairs", "ochre_froglight", "verdant_froglight", "pearlescent_froglight"
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
