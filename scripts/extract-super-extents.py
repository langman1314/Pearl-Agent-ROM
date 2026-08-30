#!/usr/bin/env python3
"""Safely stream logical partitions from a super.zst member in a ROM ZIP.

The source ZIP is opened read-only. Only linear LP extents (target_type=0,
source=0) are accepted. Outputs remain .partial until the entire zstd stream
and wrapping ZIP member have reached EOF and every declared size is verified.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

import zstandard

CHUNK_SIZE = 8 * 1024 * 1024
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")


@dataclass(frozen=True)
class Segment:
    partition: str
    source_start: int
    source_end: int
    output_start: int


@dataclass
class Output:
    name: str
    expected_size: int
    partial_path: Path
    final_path: Path
    handle: BinaryIO
    written: int = 0


def positive_int(value: object, label: str) -> int:
    if not isinstance(value, int) or value < 0:
        raise ValueError(f"{label} must be a non-negative integer")
    return value


def parse_layout(path: Path) -> tuple[list[Segment], dict[str, int], int]:
    document = json.loads(path.read_text(encoding="utf-8"))
    block_devices = document.get("block_devices")
    if not isinstance(block_devices, list) or len(block_devices) != 1:
        raise ValueError("layout must describe exactly one super block device")
    device_size = positive_int(block_devices[0].get("size"), "block device size")
    if device_size == 0:
        raise ValueError("super block device size is zero")
    partitions = document.get("partitions")
    if not isinstance(partitions, list):
        raise ValueError("layout JSON has no partitions array")

    segments: list[Segment] = []
    sizes: dict[str, int] = {}
    for item in partitions:
        if not isinstance(item, dict):
            raise ValueError("partition record is not an object")
        name = item.get("name")
        if not isinstance(name, str) or not SAFE_NAME.fullmatch(name):
            raise ValueError(f"unsafe partition name: {name!r}")
        total_size = positive_int(item.get("total_size"), f"{name}.total_size")
        extents = item.get("extents")
        if not isinstance(extents, list):
            raise ValueError(f"{name}.extents is not an array")
        if total_size == 0:
            if extents:
                raise ValueError(f"empty partition {name} unexpectedly has extents")
            continue
        if name in sizes:
            raise ValueError(f"duplicate partition name: {name}")

        output_start = 0
        for index, extent in enumerate(extents):
            if not isinstance(extent, dict):
                raise ValueError(f"{name}.extents[{index}] is not an object")
            target_type = positive_int(extent.get("target_type"), "target_type")
            source = positive_int(extent.get("source"), "source")
            source_start = positive_int(extent.get("offset"), "offset")
            extent_size = positive_int(extent.get("size"), "size")
            if target_type != 0 or source != 0:
                raise ValueError(
                    f"{name} extent {index} is not a linear super-device extent"
                )
            if extent_size == 0:
                raise ValueError(f"{name} extent {index} is empty")
            source_end = source_start + extent_size
            segments.append(
                Segment(name, source_start, source_end, output_start)
            )
            output_start += extent_size
        if output_start != total_size:
            raise ValueError(
                f"{name} extent sum {output_start} != total_size {total_size}"
            )
        sizes[name] = total_size

    if not segments:
        raise ValueError("layout contains no populated linear partitions")
    ordered = sorted(segments, key=lambda segment: segment.source_start)
    for previous, current in zip(ordered, ordered[1:]):
        if previous.source_end > current.source_start:
            raise ValueError(
                f"overlapping source extents: {previous.partition}/{current.partition}"
            )
    return ordered, sizes, device_size


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def extract(args: argparse.Namespace) -> None:
    rom_zip = args.rom_zip.resolve(strict=True)
    layout_json = args.layout_json.resolve(strict=True)
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    segments, sizes, expected_device_size = parse_layout(layout_json)

    outputs: dict[str, Output] = {}
    try:
        for name, expected_size in sizes.items():
            final_path = output_dir / f"{name}.img"
            partial_path = output_dir / f"{name}.img.partial"
            if final_path.exists() and not args.force:
                raise FileExistsError(
                    f"refusing to replace existing output without --force: {final_path}"
                )
            partial_path.unlink(missing_ok=True)
            outputs[name] = Output(
                name,
                expected_size,
                partial_path,
                final_path,
                partial_path.open("w+b"),
            )

        with zipfile.ZipFile(rom_zip, "r") as archive:
            try:
                info = archive.getinfo(args.super_entry)
            except KeyError as error:
                raise ValueError(
                    f"ROM ZIP has no exact member {args.super_entry!r}"
                ) from error
            if info.is_dir():
                raise ValueError("super entry is a directory")
            with archive.open(info, "r") as compressed:
                decompressor = zstandard.ZstdDecompressor()
                with decompressor.stream_reader(
                    compressed, read_across_frames=True, closefd=False
                ) as super_stream:
                    position = 0
                    while chunk := super_stream.read(CHUNK_SIZE):
                        chunk_end = position + len(chunk)
                        for segment in segments:
                            overlap_start = max(position, segment.source_start)
                            overlap_end = min(chunk_end, segment.source_end)
                            if overlap_start >= overlap_end:
                                continue
                            output = outputs[segment.partition]
                            output_offset = (
                                segment.output_start
                                + overlap_start
                                - segment.source_start
                            )
                            data_start = overlap_start - position
                            data_end = overlap_end - position
                            output.handle.seek(output_offset)
                            output.handle.write(chunk[data_start:data_end])
                            output.written += data_end - data_start
                        position = chunk_end
                if position != expected_device_size:
                    raise ValueError(
                        f"decompressed super size {position} != declared "
                        f"device size {expected_device_size}"
                    )
                # ZipExtFile validates CRC only after its member reaches EOF.
                while compressed.read(CHUNK_SIZE):
                    pass

        for output in outputs.values():
            output.handle.flush()
            os.fsync(output.handle.fileno())
            output.handle.close()
            if output.written != output.expected_size:
                raise ValueError(
                    f"{output.name}: wrote {output.written}, expected "
                    f"{output.expected_size} bytes"
                )
            if output.partial_path.stat().st_size != output.expected_size:
                raise ValueError(f"{output.name}: sparse/output size mismatch")

        for output in outputs.values():
            if args.force:
                output.final_path.unlink(missing_ok=True)
            output.partial_path.replace(output.final_path)

        manifest_path = output_dir / "logical-partitions.sha256"
        manifest_partial = output_dir / "logical-partitions.sha256.partial"
        with manifest_partial.open("w", encoding="ascii", newline="\n") as manifest:
            for name in sorted(outputs):
                final_path = outputs[name].final_path
                manifest.write(f"{hash_file(final_path)}  {final_path.name}\n")
        manifest_partial.replace(manifest_path)
        print(f"Validated {len(outputs)} partitions from {args.super_entry}")
        print(f"SHA-256 manifest: {manifest_path}")
    except Exception:
        for output in outputs.values():
            if not output.handle.closed:
                output.handle.close()
            output.partial_path.unlink(missing_ok=True)
        raise


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--rom-zip", type=Path, required=True)
    result.add_argument("--layout-json", type=Path, required=True)
    result.add_argument("--output-dir", type=Path, required=True)
    result.add_argument("--super-entry", default="super.zst")
    result.add_argument("--force", action="store_true")
    return result


if __name__ == "__main__":
    try:
        extract(parser().parse_args())
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
