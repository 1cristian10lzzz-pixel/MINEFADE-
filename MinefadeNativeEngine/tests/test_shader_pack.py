#!/usr/bin/env python3
from pathlib import Path
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.validate_mfshader import parse_shader


class ShaderPackTests(unittest.TestCase):
    def test_valid_shader_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vibrant.mfshader"
            path.write_text(
                "format=1\nid=minefade_vibrant\nname=Minefade Vibrante\nversion=1.0\n"
                "water.waves=true\nlava.animation=true\nleaves.wind=true\nleaves.strength=0.05\n",
                encoding="utf-8")
            self.assertEqual(parse_shader(path)["id"], "minefade_vibrant")

    def test_shader_code_and_unknown_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsafe.mfshader"
            path.write_text("format=1\nid=unsafe\nname=Unsafe\nversion=1\nfragment=while(true){}\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "no soportada"):
                parse_shader(path)

    def test_leaf_strength_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.mfshader"
            path.write_text("format=1\nid=bad_profile\nname=Bad\nversion=1\nleaves.strength=0.5\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "fuera de rango"):
                parse_shader(path)


if __name__ == "__main__":
    unittest.main()
