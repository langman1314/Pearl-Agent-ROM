#!/usr/bin/env python3
"""Convert a raw image to Android sparse v1 without third-party binaries."""

from __future__ import annotations

import argparse
import os
import struct
from pathlib import Path

SPARSE_MAGIC = 0xED26FF3A
CHUNK_RAW = 0xCAC1
CHUNK_FILL = 0xCAC2
FILE_HEADER = struct.Struct("<I4H4I")
CHUNK_HEADER = struct.Struct("<2H2I")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--block-size", type=int, default=4096)
    return parser.parse_args()


def convert(source: Path, destination: Path, block_size: int) -> None:
    if destination.exists():
        raise FileExistsError(f"refusing to overwrite: {destination}")
    if block_size <= 0 or block_size % 4:
        raise ValueError("block size must be a positive multiple of four")
    size = source.stat().st_size
    if size % block_size:
        raise ValueError(f"raw image size {size} is not block aligned to {block_size}")
    total_blocks = size // block_size
    max_raw_blocks = (0xFFFFFFFF - CHUNK_HEADER.size) // block_size
    if total_blocks > 0xFFFFFFFF:
        raise ValueError("raw image has too many blocks for Android sparse v1")

    partial = destination.with_name(destination.name + f".partial.{os.getpid()}")
    partial.parent.mkdir(parents=True, exist_ok=True)
    zero = bytes(block_size)
    chunks = 0
    current_type: int | None = None
    current_blocks = 0
    header_offset = 0

    def close_chunk(output) -> None:
        nonlocal chunks, current_type, current_blocks, header_offset
        if current_type is None:
            return
        end = output.tell()
        total_size = CHUNK_HEADER.size
        if current_type == CHUNK_RAW:
            total_size += current_blocks * block_size
        elif current_type == CHUNK_FILL:
            total_size += 4
        output.seek(header_offset)
        output.write(CHUNK_HEADER.pack(current_type, 0, current_blocks, total_size))
        output.seek(end)
        chunks += 1
        current_type = None
        current_blocks = 0

    try:
        with source.open("rb") as src, partial.open("w+b") as out:
            out.write(bytes(FILE_HEADER.size))
            for _ in range(total_blocks):
                block = src.read(block_size)
                if len(block) != block_size:
                    raise EOFError("raw image ended before declared size")
                chunk_type = CHUNK_FILL if block == zero else CHUNK_RAW
                must_split = (
                    current_type is not None
                    and (
                        chunk_type != current_type
                        or (current_type == CHUNK_RAW and current_blocks >= max_raw_blocks)
                    )
                )
                if must_split:
                    close_chunk(out)
                if current_type is None:
                    current_type = chunk_type
                    current_blocks = 0
                    header_offset = out.tell()
                    out.write(bytes(CHUNK_HEADER.size))
                    if chunk_type == CHUNK_FILL:
                        out.write(bytes(4))
                if chunk_type == CHUNK_RAW:
                    out.write(block)
                current_blocks += 1
            close_chunk(out)
            if src.read(1):
                raise ValueError("raw image grew during conversion")
            out.seek(0)
            out.write(
                FILE_HEADER.pack(
                    SPARSE_MAGIC,
                    1,
                    0,
                    FILE_HEADER.size,
                    CHUNK_HEADER.size,
                    block_size,
                    total_blocks,
                    chunks,
                    0,
                )
            )
            out.flush()
            os.fsync(out.fileno())
        os.replace(partial, destination)
    except BaseException:
        partial.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    args = parse_args()
    convert(args.input, args.output, args.block_size)
