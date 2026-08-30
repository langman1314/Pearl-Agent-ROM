#!/usr/bin/env python3
"""Strictly expand an Android sparse image without modifying the input.

RAW, non-zero FILL, DONT_CARE, and CRC32 chunks are validated against the
Android sparse format. Output is written to a sibling `.partial` file, fsynced,
size-checked, and atomically published only after every declared chunk and the
input EOF have been consumed.
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
from pathlib import Path
from typing import BinaryIO

SPARSE_HEADER_MAGIC = 0xED26FF3A
SPARSE_HEADER = struct.Struct("<I4H4I")
CHUNK_HEADER = struct.Struct("<2H2I")
CHUNK_RAW = 0xCAC1
CHUNK_FILL = 0xCAC2
CHUNK_DONT_CARE = 0xCAC3
CHUNK_CRC32 = 0xCAC4
COPY_SIZE = 8 * 1024 * 1024


class SparseFormatError(ValueError):
    pass


def read_exact(stream: BinaryIO, size: int, label: str) -> bytes:
    data = stream.read(size)
    if len(data) != size:
        raise SparseFormatError(f"truncated {label}: got {len(data)}, expected {size}")
    return data


def copy_exact(source: BinaryIO, target: BinaryIO, size: int, label: str) -> None:
    remaining = size
    while remaining:
        data = read_exact(source, min(remaining, COPY_SIZE), label)
        target.write(data)
        remaining -= len(data)


def write_fill(target: BinaryIO, pattern: bytes, size: int) -> None:
    if len(pattern) != 4 or size % 4:
        raise SparseFormatError("FILL output must be aligned to its four-byte pattern")
    block = pattern * (min(size, COPY_SIZE) // 4)
    remaining = size
    while remaining:
        data = block[: min(remaining, len(block))]
        target.write(data)
        remaining -= len(data)


def expand_sparse(source_path: Path, output_path: Path, force: bool = False) -> None:
    source_path = source_path.resolve(strict=True)
    output_path = output_path.resolve()
    if source_path == output_path:
        raise SparseFormatError("input and output paths must differ")
    if output_path.exists() and not force:
        raise FileExistsError(f"refusing to replace output without --force: {output_path}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    partial_path = output_path.with_name(output_path.name + ".partial")
    partial_path.unlink(missing_ok=True)

    try:
        with source_path.open("rb") as source, partial_path.open("w+b") as target:
            header_data = read_exact(source, SPARSE_HEADER.size, "sparse header")
            (
                magic,
                major_version,
                minor_version,
                file_header_size,
                chunk_header_size,
                block_size,
                total_blocks,
                total_chunks,
                _image_checksum,
            ) = SPARSE_HEADER.unpack(header_data)
            if magic != SPARSE_HEADER_MAGIC:
                raise SparseFormatError(f"bad sparse magic: 0x{magic:08x}")
            if major_version != 1:
                raise SparseFormatError(
                    f"unsupported sparse version: {major_version}.{minor_version}"
                )
            if file_header_size < SPARSE_HEADER.size:
                raise SparseFormatError("sparse file header is smaller than version 1")
            if chunk_header_size < CHUNK_HEADER.size:
                raise SparseFormatError("sparse chunk header is smaller than version 1")
            if block_size == 0 or block_size % 4:
                raise SparseFormatError(f"invalid sparse block size: {block_size}")
            read_exact(
                source,
                file_header_size - SPARSE_HEADER.size,
                "extended sparse header",
            )

            expanded_blocks = 0
            for index in range(total_chunks):
                chunk_data = read_exact(source, CHUNK_HEADER.size, f"chunk {index} header")
                chunk_type, _reserved, chunk_blocks, total_size = CHUNK_HEADER.unpack(
                    chunk_data
                )
                read_exact(
                    source,
                    chunk_header_size - CHUNK_HEADER.size,
                    f"chunk {index} extended header",
                )
                if total_size < chunk_header_size:
                    raise SparseFormatError(f"chunk {index} total size is too small")
                payload_size = total_size - chunk_header_size
                output_size = chunk_blocks * block_size

                if chunk_type == CHUNK_RAW:
                    if payload_size != output_size:
                        raise SparseFormatError(
                            f"RAW chunk {index} payload {payload_size} != output {output_size}"
                        )
                    copy_exact(source, target, payload_size, f"RAW chunk {index}")
                elif chunk_type == CHUNK_FILL:
                    if payload_size != 4:
                        raise SparseFormatError(
                            f"FILL chunk {index} payload {payload_size} != 4"
                        )
                    pattern = read_exact(source, 4, f"FILL chunk {index}")
                    write_fill(target, pattern, output_size)
                elif chunk_type == CHUNK_DONT_CARE:
                    if payload_size != 0:
                        raise SparseFormatError(
                            f"DONT_CARE chunk {index} unexpectedly has a payload"
                        )
                    target.seek(output_size, os.SEEK_CUR)
                elif chunk_type == CHUNK_CRC32:
                    if payload_size != 4 or chunk_blocks != 0:
                        raise SparseFormatError(
                            f"CRC32 chunk {index} must contain four bytes and zero blocks"
                        )
                    read_exact(source, 4, f"CRC32 chunk {index}")
                else:
                    raise SparseFormatError(
                        f"unsupported chunk type 0x{chunk_type:04x} at index {index}"
                    )
                expanded_blocks += chunk_blocks

            if expanded_blocks != total_blocks:
                raise SparseFormatError(
                    f"expanded block count {expanded_blocks} != declared {total_blocks}"
                )
            if source.read(1):
                raise SparseFormatError("trailing bytes after declared sparse chunks")

            expected_size = total_blocks * block_size
            target.truncate(expected_size)
            target.flush()
            os.fsync(target.fileno())
            if target.tell() > expected_size or partial_path.stat().st_size != expected_size:
                raise SparseFormatError("expanded output size mismatch")

        if force:
            output_path.unlink(missing_ok=True)
        partial_path.replace(output_path)
    except Exception:
        partial_path.unlink(missing_ok=True)
        raise


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("input", type=Path)
    result.add_argument("output", type=Path)
    result.add_argument("--force", action="store_true")
    return result


if __name__ == "__main__":
    try:
        args = parser().parse_args()
        expand_sparse(args.input, args.output, args.force)
        print(f"Expanded Android sparse image: {args.output}")
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
