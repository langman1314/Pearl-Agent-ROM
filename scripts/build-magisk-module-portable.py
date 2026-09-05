#!/usr/bin/env python3
"""Build the Pearl Magisk module on hosts without rsync/zip.

The embedded rootfs and ARM64 zstd identities are still verified before packaging.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path

SOURCE_DATE_TIME = (2025, 6, 1, 0, 0, 0)
ROOTFS_NAME = "rootfs.tar.zst"
ZSTD_NAME = "zstd"
APPROVED_ROOTFS_SHA256 = "53f59ea09bb065643a0bc8de49b727fbf1b386a3b2cef2c564e329037b70cb87"
APPROVED_ZSTD_SHA256 = "a24c13c263518fc5b565407ecf0661b4cb03f724f77f0fe185be854b9a96bc09"
APPROVED_UNPACKED_BYTES = 1_034_997_760


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(8 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def read_sidecar(path: Path) -> str:
    fields = path.read_text(encoding="utf-8").split()
    if not fields or len(fields[0]) != 64:
        raise ValueError(f"invalid SHA-256 sidecar: {path}")
    return fields[0].lower()


def zip_mode(path: Path, relative: str) -> int:
    if relative.endswith("/"):
        return stat.S_IFDIR | 0o755
    executable = relative in {
        "customize.sh",
        "post-fs-data.sh",
        "service.sh",
        "action.sh",
        "uninstall.sh",
        "lib/common.sh",
        "payload/zstd",
    }
    return stat.S_IFREG | (0o755 if executable else 0o644)


def add_entry(archive: zipfile.ZipFile, path: Path, relative: str) -> None:
    info = zipfile.ZipInfo(relative, SOURCE_DATE_TIME)
    info.create_system = 3
    info.external_attr = zip_mode(path, relative) << 16
    if relative.endswith("/"):
        archive.writestr(info, b"", compress_type=zipfile.ZIP_STORED)
        return
    compression = zipfile.ZIP_STORED if relative in {
        "payload/rootfs.tar.zst",
        "payload/zstd",
    } else zipfile.ZIP_DEFLATED
    with path.open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
        shutil.copyfileobj(source, target, 8 * 1024 * 1024)
    # zipfile.open() uses the ZipInfo compression setting.
    if compression != info.compress_type:
        raise RuntimeError(f"unexpected compression mode for {relative}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rootfs", required=True, type=Path)
    parser.add_argument("--zstd", required=True, type=Path)
    parser.add_argument("--zstd-sha256", required=True)
    parser.add_argument("--unpacked-bytes-file", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    template = root / "magisk-module"
    rootfs = args.rootfs.resolve()
    zstd = args.zstd.resolve()
    expected_rootfs = read_sidecar(Path(f"{rootfs}.sha256"))
    actual_rootfs = sha256(rootfs)
    if actual_rootfs != expected_rootfs:
        raise SystemExit("rootfs SHA-256 mismatch")
    if actual_rootfs != APPROVED_ROOTFS_SHA256:
        raise SystemExit("rootfs is not the audited Pearl release payload")
    actual_zstd = sha256(zstd)
    if actual_zstd != args.zstd_sha256.lower():
        raise SystemExit("zstd SHA-256 mismatch")
    if actual_zstd != APPROVED_ZSTD_SHA256:
        raise SystemExit("zstd is not the audited static ARM64 decoder")

    properties = dict(
        line.split("=", 1)
        for line in (template / "module.prop").read_text(encoding="utf-8").splitlines()
        if "=" in line
    )
    version = properties["version"]
    version_code = properties["versionCode"]
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    artifact = output_dir / f"pearl-agent-magisk-{version}-{version_code}.zip"
    sidecar = Path(f"{artifact}.sha256")
    if artifact.exists() or sidecar.exists():
        raise SystemExit(f"refusing to overwrite immutable release output: {artifact}")

    with tempfile.TemporaryDirectory(prefix="pearl-magisk-") as temp_name:
        stage = Path(temp_name) / "module"
        shutil.copytree(template, stage)
        payload = stage / "payload"
        shutil.copyfile(rootfs, payload / ROOTFS_NAME)
        shutil.copyfile(zstd, payload / ZSTD_NAME)
        unpacked_file = args.unpacked_bytes_file.resolve()
        unpacked_text = unpacked_file.read_text(encoding="ascii").strip()
        if not unpacked_text.isdigit() or int(unpacked_text) != APPROVED_UNPACKED_BYTES:
            raise SystemExit("rootfs uncompressed-size metadata does not match audited release")
        (payload / "rootfs.unpacked-bytes").write_text(
            f"{unpacked_text}\n", encoding="ascii", newline="\n"
        )
        entries = [
            (actual_rootfs, ROOTFS_NAME),
            (actual_zstd, ZSTD_NAME),
            (sha256(payload / "rootfs.unpacked-bytes"), "rootfs.unpacked-bytes"),
            (sha256(payload / "hermes-config.yaml"), "hermes-config.yaml"),
        ]
        (payload / "manifest.sha256").write_text(
            "".join(f"{digest}  {name}\n" for digest, name in entries),
            encoding="utf-8",
            newline="\n",
        )

        paths = sorted(stage.rglob("*"), key=lambda item: item.relative_to(stage).as_posix())
        with zipfile.ZipFile(artifact, "w", allowZip64=True) as archive:
            for path in paths:
                relative = path.relative_to(stage).as_posix()
                if path.is_dir():
                    add_entry(archive, path, f"{relative}/")
                else:
                    info = zipfile.ZipInfo(relative, SOURCE_DATE_TIME)
                    info.create_system = 3
                    info.external_attr = zip_mode(path, relative) << 16
                    info.compress_type = (
                        zipfile.ZIP_STORED
                        if relative in {"payload/rootfs.tar.zst", "payload/zstd"}
                        else zipfile.ZIP_DEFLATED
                    )
                    with path.open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
                        shutil.copyfileobj(source, target, 8 * 1024 * 1024)

    artifact_hash = sha256(artifact)
    sidecar.write_text(f"{artifact_hash}  {artifact.name}\n", encoding="utf-8", newline="\n")
    print(f"Artifact: {artifact}")
    print(f"SHA-256: {artifact_hash}")


if __name__ == "__main__":
    main()
