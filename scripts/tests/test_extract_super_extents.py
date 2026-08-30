import argparse
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import zstandard


SCRIPT = Path(__file__).parents[1] / "extract-super-extents.py"
SPEC = importlib.util.spec_from_file_location("extract_super_extents", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ExtractSuperExtentsTest(unittest.TestCase):
    def test_streams_linear_extents_and_hashes_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            super_image = bytes((index % 251 for index in range(128 * 1024)))
            layout = {
                "block_devices": [{"name": "super", "size": len(super_image)}],
                "partitions": [
                    {
                        "name": "alpha_a",
                        "total_size": 8192,
                        "extents": [
                            {
                                "target_type": 0,
                                "source": 0,
                                "offset": 4096,
                                "size": 8192,
                            }
                        ],
                    },
                    {
                        "name": "beta_a",
                        "total_size": 6144,
                        "extents": [
                            {
                                "target_type": 0,
                                "source": 0,
                                "offset": 32768,
                                "size": 2048,
                            },
                            {
                                "target_type": 0,
                                "source": 0,
                                "offset": 65536,
                                "size": 4096,
                            },
                        ],
                    },
                    {"name": "empty_b", "total_size": 0, "extents": []},
                ],
            }
            layout_path = root / "layout.json"
            layout_path.write_text(json.dumps(layout), encoding="utf-8")
            rom_zip = root / "rom.zip"
            compressed = zstandard.ZstdCompressor(level=1).compress(super_image)
            with zipfile.ZipFile(rom_zip, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("super.zst", compressed)

            output_dir = root / "out"
            MODULE.extract(
                argparse.Namespace(
                    rom_zip=rom_zip,
                    layout_json=layout_path,
                    output_dir=output_dir,
                    super_entry="super.zst",
                    force=False,
                )
            )

            alpha = super_image[4096:12288]
            beta = super_image[32768:34816] + super_image[65536:69632]
            self.assertEqual(alpha, (output_dir / "alpha_a.img").read_bytes())
            self.assertEqual(beta, (output_dir / "beta_a.img").read_bytes())
            manifest = (output_dir / "logical-partitions.sha256").read_text("ascii")
            self.assertIn(
                f"{hashlib.sha256(alpha).hexdigest()}  alpha_a.img\n", manifest
            )
            self.assertIn(
                f"{hashlib.sha256(beta).hexdigest()}  beta_a.img\n", manifest
            )
            self.assertFalse(list(output_dir.glob("*.partial")))

    def test_rejects_non_linear_extent(self):
        with tempfile.TemporaryDirectory() as temporary:
            layout_path = Path(temporary) / "layout.json"
            layout_path.write_text(
                json.dumps(
                    {
                        "block_devices": [{"name": "super", "size": 4096}],
                        "partitions": [
                            {
                                "name": "bad_a",
                                "total_size": 4096,
                                "extents": [
                                    {
                                        "target_type": 1,
                                        "source": 0,
                                        "offset": 0,
                                        "size": 4096,
                                    }
                                ],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "not a linear"):
                MODULE.parse_layout(layout_path)


if __name__ == "__main__":
    unittest.main()
