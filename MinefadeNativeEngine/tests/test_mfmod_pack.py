#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.validate_mfmod import parse_mod
SAMPLE = ROOT / "mods" / "Minefade_Ultimate_Core_0.1.mfmod"
BUNDLED = ROOT / "app/src/main/assets/mods/minefade_ultimate_core.mfmod"


class ModPackTests(unittest.TestCase):
    def test_sample_pack_is_valid_and_disabled_by_default(self):
        values = parse_mod(SAMPLE)
        self.assertEqual(values["id"], "minefade_ultimate_core")
        self.assertEqual(values["enabled"], "false")
        self.assertEqual(values["player.speed"], "1.08")
        self.assertEqual(values["world.day_speed"], "1.05")

    def test_bundled_pack_is_valid_and_enabled_by_default(self):
        values = parse_mod(BUNDLED)
        self.assertEqual(values["id"], "minefade_ultimate_core")
        self.assertEqual(values["enabled"], "true")
        self.assertEqual(values["player.speed"], "1.08")

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

    def test_new_gameplay_tuning_settings_are_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "advanced.mfmod"
            path.write_text(
                "id=advanced_mod\\nname=Advanced\\nversion=1.0\\nenabled=true\\n"
                "player.jump=1.2\\nentity.speed=1.4\\n",
                encoding="utf-8",
            )
            values = parse_mod(path)
            self.assertEqual(values["player.jump"], "1.2")
            self.assertEqual(values["entity.speed"], "1.4")

    def test_entity_speed_out_of_range_is_rejected(self):
        self.assert_invalid(
            "id=test_mod\\nname=Test\\nversion=1.0\\nenabled=true\\nentity.speed=4.0\\n",
            "fuera de rango",
        )

    def test_jump_out_of_range_is_rejected(self):
        self.assert_invalid(
            "id=test_mod\\nname=Test\\nversion=1.0\\nenabled=true\\nplayer.jump=0.1\\n",
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
