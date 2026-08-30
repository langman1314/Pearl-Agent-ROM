from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "unsparse-android-image.py"
SPEC = importlib.util.spec_from_file_location("unsparse_android_image", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AndroidSparseImageTest(unittest.TestCase):
    def test_expands_raw_nonzero_fill_and_dont_care(self) -> None:
        block_size = 4096
        raw = b"R" * block_size
        pattern = bytes.fromhex("78563412")
        chunks = [
            self.chunk(MODULE.CHUNK_RAW, 1, raw),
            self.chunk(MODULE.CHUNK_FILL, 2, pattern),
            self.chunk(MODULE.CHUNK_DONT_CARE, 1, b""),
            self.chunk(MODULE.CHUNK_CRC32, 0, b"\x00\x00\x00\x00"),
        ]
        image = self.header(block_size, 4, len(chunks)) + b"".join(chunks)

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.sparse"
            output = Path(directory) / "output.raw"
            source.write_bytes(image)
            MODULE.expand_sparse(source, output)
            self.assertEqual(
                output.read_bytes(),
                raw + pattern * (2 * block_size // 4) + bytes(block_size),
            )
            self.assertFalse(Path(str(output) + ".partial").exists())

    def test_rejects_bad_fill_size_and_removes_partial(self) -> None:
        block_size = 4096
        chunk = self.chunk(MODULE.CHUNK_FILL, 1, b"12345678")
        image = self.header(block_size, 1, 1) + chunk

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.sparse"
            output = Path(directory) / "output.raw"
            source.write_bytes(image)
            with self.assertRaisesRegex(MODULE.SparseFormatError, "payload 8 != 4"):
                MODULE.expand_sparse(source, output)
            self.assertFalse(output.exists())
            self.assertFalse(Path(str(output) + ".partial").exists())

    def test_rejects_trailing_bytes(self) -> None:
        block_size = 4096
        chunk = self.chunk(MODULE.CHUNK_DONT_CARE, 1, b"")
        image = self.header(block_size, 1, 1) + chunk + b"unexpected"

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.sparse"
            output = Path(directory) / "output.raw"
            source.write_bytes(image)
            with self.assertRaisesRegex(MODULE.SparseFormatError, "trailing bytes"):
                MODULE.expand_sparse(source, output)
            self.assertFalse(output.exists())

    @staticmethod
    def header(block_size: int, total_blocks: int, total_chunks: int) -> bytes:
        return MODULE.SPARSE_HEADER.pack(
            MODULE.SPARSE_HEADER_MAGIC,
            1,
            0,
            MODULE.SPARSE_HEADER.size,
            MODULE.CHUNK_HEADER.size,
            block_size,
            total_blocks,
            total_chunks,
            0,
        )

    @staticmethod
    def chunk(chunk_type: int, blocks: int, payload: bytes) -> bytes:
        return MODULE.CHUNK_HEADER.pack(
            chunk_type,
            0,
            blocks,
            MODULE.CHUNK_HEADER.size + len(payload),
        ) + payload


if __name__ == "__main__":
    unittest.main()
