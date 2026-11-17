#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.validate_mfmod import parse_mod


class ModPackTests(unittest.TestCase):
    def test_demo_and_automatic_core_packs_are_not_shipped(self):
        self.assertFalse((ROOT / "mods" / "Minefade_Ultimate_Core_0.1.mfmod").exists())
        self.assertFalse((ROOT / "app/src/main/assets/mods/minefade_ultimate_core.mfmod").exists())

    def test_unknown_setting_is_rejected(self):
        self.assert_invalid(
            "id=test_mod\nname=Test\nversion=1.0\nenabled=false\nshader=ultra\n",
            "no soportada",
        )

    def test_duplicate_key_is_rejected(self):
        self.assert_invalid(
            "id=test_mod\nname=Test\nversion=1.0\nenabled=false\nenabled=true\n",
            "duplicada",
        )

    def test_out_of_range_setting_is_rejected(self):
        self.assert_invalid(
            "id=test_mod\nname=Test\nversion=1.0\nenabled=true\nplayer.speed=9.0\n",
            "fuera de rango",
        )

    def test_new_entity_spawn_types_are_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "creatures.mfmod"
            path.write_text(
                "id=creature_mod\nname=Creatures\nversion=1.0\nenabled=true\n"
                "spawn.rabbit=2\nspawn.zombie=1\nspawn.skeleton=1\n",
                encoding="utf-8",
            )
            values = parse_mod(path)
            self.assertEqual(values["spawn.rabbit"], "2")
            self.assertEqual(values["spawn.zombie"], "1")
            self.assertEqual(values["spawn.skeleton"], "1")

    def test_new_gameplay_tuning_settings_are_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "advanced.mfmod"
            path.write_text(
                "id=advanced_mod\nname=Advanced\nversion=1.0\nenabled=true\n"
                "player.jump=1.2\nentity.speed=1.4\n",
                encoding="utf-8",
            )
            values = parse_mod(path)
            self.assertEqual(values["player.jump"], "1.2")
            self.assertEqual(values["entity.speed"], "1.4")

    def test_entity_speed_out_of_range_is_rejected(self):
        self.assert_invalid(
            "id=test_mod\nname=Test\nversion=1.0\nenabled=true\nentity.speed=4.0\n",
            "fuera de rango",
        )

    def test_jump_out_of_range_is_rejected(self):
        self.assert_invalid(
            "id=test_mod\nname=Test\nversion=1.0\nenabled=true\nplayer.jump=0.1\n",
            "fuera de rango",
        )

    def assert_invalid(self, content, message):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.mfmod"
            path.write_text(content, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, message):
                parse_mod(path)


if __name__ == "__main__":
    unittest.main()
