import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


converter = load("raw_to_sparse", ROOT / "scripts" / "raw-to-android-sparse.py")
expander = load("strict_unsparse", ROOT / "scripts" / "unsparse-android-image.py")


class RawToAndroidSparseTest(unittest.TestCase):
    def test_round_trip_preserves_raw_bytes(self):
        block = 4096
        content = (
            b"A" * block
            + bytes(block * 3)
            + bytes(range(256)) * (block // 256)
            + bytes(block * 2)
        )
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            raw = directory / "super.raw.img"
            sparse = directory / "super.img"
            restored = directory / "super.restored.img"
            raw.write_bytes(content)

            converter.convert(raw, sparse, block)
            expander.expand_sparse(sparse, restored)

            self.assertEqual(content, restored.read_bytes())
            header = struct.unpack("<I4H4I", sparse.read_bytes()[:28])
            self.assertEqual(0xED26FF3A, header[0])
            self.assertEqual(len(content) // block, header[6])
            self.assertEqual(4, header[7])
            self.assertEqual(0, header[8])
            self.assertLess(sparse.stat().st_size, raw.stat().st_size)

            encoded = sparse.read_bytes()
            offset = 28
            chunk_types = []
            for _ in range(header[7]):
                chunk_type, _, chunk_blocks, total_size = struct.unpack(
                    "<2H2I", encoded[offset : offset + 12]
                )
                chunk_types.append(chunk_type)
                offset += total_size
            self.assertIn(0xCAC2, chunk_types)
            self.assertNotIn(0xCAC3, chunk_types)

    def test_rejects_unaligned_input(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            raw = directory / "bad.raw"
            raw.write_bytes(b"unaligned")
            with self.assertRaises(ValueError):
                converter.convert(raw, directory / "bad.sparse", 4096)

    def test_refuses_to_overwrite_output(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            raw = directory / "raw.img"
            sparse = directory / "sparse.img"
            raw.write_bytes(bytes(4096))
            sparse.write_bytes(b"keep")
            with self.assertRaises(FileExistsError):
                converter.convert(raw, sparse, 4096)
            self.assertEqual(b"keep", sparse.read_bytes())


if __name__ == "__main__":
    unittest.main()
