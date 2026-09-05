from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "build-magisk-module-portable.py"
SPEC = importlib.util.spec_from_file_location("build_magisk_module_portable", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PortableMagiskBuildTest(unittest.TestCase):
    def test_approved_payload_hashes_are_pinned(self) -> None:
        self.assertEqual(
            MODULE.APPROVED_ROOTFS_SHA256,
            "53f59ea09bb065643a0bc8de49b727fbf1b386a3b2cef2c564e329037b70cb87",
        )
        self.assertEqual(
            MODULE.APPROVED_ZSTD_SHA256,
            "a24c13c263518fc5b565407ecf0661b4cb03f724f77f0fe185be854b9a96bc09",
        )
        self.assertEqual(MODULE.APPROVED_UNPACKED_BYTES, 1_034_997_760)
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("refusing to overwrite immutable release output", source)
        self.assertNotIn("artifact.unlink", source)

    def test_zip_modes_and_timestamps_are_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            temp = Path(temp_name)
            executable = temp / "customize.sh"
            executable.write_text("#!/system/bin/sh\n", encoding="utf-8")
            regular = temp / "module.prop"
            regular.write_text("id=test\n", encoding="utf-8")
            outputs = []
            for index in range(2):
                archive_path = temp / f"test-{index}.zip"
                with zipfile.ZipFile(archive_path, "w") as archive:
                    for path in (executable, regular):
                        relative = path.name
                        info = zipfile.ZipInfo(relative, MODULE.SOURCE_DATE_TIME)
                        info.create_system = 3
                        info.external_attr = MODULE.zip_mode(path, relative) << 16
                        info.compress_type = zipfile.ZIP_DEFLATED
                        archive.writestr(info, path.read_bytes())
                outputs.append(archive_path.read_bytes())
                with zipfile.ZipFile(archive_path) as archive:
                    self.assertEqual(archive.getinfo("customize.sh").external_attr >> 16, 0o100755)
                    self.assertEqual(archive.getinfo("module.prop").external_attr >> 16, 0o100644)
                    self.assertEqual(archive.getinfo("customize.sh").date_time, MODULE.SOURCE_DATE_TIME)
            self.assertEqual(outputs[0], outputs[1])


if __name__ == "__main__":
    unittest.main()
