#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest
from tools.validate_mfmod import parse_mod

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "mods" / "Minefade_Ultimate_Core_0.1.mfmod"


class ModPackTests(unittest.TestCase):
    def test_sample_pack_is_valid_and_disabled_by_default(self):
        values = parse_mod(SAMPLE)
        self.assertEqual(values["id"], "minefade_ultimate_core")
        self.assertEqual(values["enabled"], "false")
        self.assertEqual(values["player.speed"], "1.08")
        self.assertEqual(values["world.day_speed"], "1.05")

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

    def assert_invalid(self, content, message):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.mfmod"
            path.write_text(content, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, message):
                parse_mod(path)


if __name__ == "__main__":
    unittest.main()
