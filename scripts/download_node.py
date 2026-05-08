#!/usr/bin/env python3
"""
Download Node.js and required shared libraries from Termux's package repository.
No Android phone needed — everything is fetched from https://packages.termux.dev.

Usage:
    python scripts/download_node.py

Output:
    app/src/main/jniLibs/arm64-v8a/libnode.so
    app/src/main/assets/nodelibs.zip
"""

import gzip
import io
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
JNILIBS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
WORK_DIR = PROJECT_ROOT / "build_workspace" / "termux_pkgs"

TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"
PACKAGES_URL = f"{TERMUX_REPO}/dists/stable/main/binary-aarch64/Packages.gz"

# Packages we need to download
REQUIRED_PACKAGES = [
    "nodejs",
    "libc++",
    "zlib",
    "openssl",
    "c-ares",
    "libnghttp2",
    "libnghttp3",
    "libngtcp2",
    "brotli",
    "libsqlite",
    "libandroid-posix-semaphore",
    "libicu",
]

# Library files we want to extract (glob patterns matched by startswith)
WANTED_LIBS = [
    "libc++_shared.so",
    "libz.so",
    "libcrypto.so",
    "libssl.so",
    "libcares.so",
    "libnghttp2.so",
    "libnghttp3.so",
    "libngtcp2",
    "libbrotlicommon.so",
    "libbrotlidec.so",
    "libbrotlienc.so",
    "libsqlite3.so",
    "libandroid-posix-semaphore.so",
    "libicudata.so",
    "libicui18n.so",
    "libicuuc.so",
]

# Skip test/tool ICU libs
SKIP_LIBS = ["libicutest", "libicutu", "libicuio"]


def download(url: str, desc: str = "") -> bytes:
    """Download a URL and return bytes."""
    print(f"  Downloading {desc or url}...")
    req = urllib.request.Request(url, headers={"User-Agent": "SillyTavern-Android-Builder/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    print(f"    {len(data) / 1024 / 1024:.1f} MB")
    return data


def parse_packages_index(data: bytes) -> dict:
    """Parse Termux Packages.gz index into a dict of package_name -> info."""
    text = gzip.decompress(data).decode("utf-8")
    packages = {}
    current = {}

    for line in text.split("\n"):
        if line == "":
            if "Package" in current:
                packages[current["Package"]] = current
            current = {}
        elif ": " in line:
            key, val = line.split(": ", 1)
            current[key] = val

    if "Package" in current:
        packages[current["Package"]] = current

    return packages


def extract_deb(deb_data: bytes, extract_dir: Path):
    """Extract files from a .deb package (ar archive containing data.tar.xz)."""
    # .deb is an 'ar' archive. We need to find and extract data.tar.xz
    # ar format: "!<arch>\n" header, then entries
    offset = 8  # skip "!<arch>\n"

    while offset < len(deb_data):
        # ar entry header: 60 bytes
        if offset + 60 > len(deb_data):
            break
        name = deb_data[offset:offset + 16].decode("ascii").strip()
        size_str = deb_data[offset + 48:offset + 58].decode("ascii").strip()
        size = int(size_str)
        offset += 60  # skip header

        if name.startswith("data.tar"):
            file_data = deb_data[offset:offset + size]

            if name.endswith(".xz") or name.startswith("data.tar.xz"):
                import lzma
                tar_data = lzma.decompress(file_data)
            elif name.endswith(".gz"):
                tar_data = gzip.decompress(file_data)
            elif name.endswith(".zst"):
                # zstandard might not be available, try subprocess
                try:
                    import zstandard as zstd
                    dctx = zstd.ZstdDecompressor()
                    tar_data = dctx.decompress(file_data, max_output_size=500 * 1024 * 1024)
                except ImportError:
                    # Fall back to zstd command
                    result = subprocess.run(
                        ["zstd", "-d", "--stdout"],
                        input=file_data, capture_output=True
                    )
                    if result.returncode != 0:
                        print(f"    ERROR: zstd decompression failed. Install: pip install zstandard")
                        sys.exit(1)
                    tar_data = result.stdout
            else:
                tar_data = file_data

            with tarfile.open(fileobj=io.BytesIO(tar_data)) as tf:
                tf.extractall(extract_dir)
            return

        offset += size
        if offset % 2 == 1:
            offset += 1  # ar entries are 2-byte aligned

    print("    ERROR: data.tar not found in .deb")
    sys.exit(1)


def should_include_lib(filename: str) -> bool:
    """Check if a .so file should be included."""
    for skip in SKIP_LIBS:
        if filename.startswith(skip):
            return False
    for wanted in WANTED_LIBS:
        if filename.startswith(wanted.split(".so")[0]):
            return True
    return False


def main():
    print("=" * 60)
    print("  Download Node.js from Termux Package Repository")
    print("=" * 60)

    # Step 1: Fetch package index
    print("\n[1/4] Fetching package index...")
    pkg_data = download(PACKAGES_URL, "Packages.gz")
    packages = parse_packages_index(pkg_data)
    print(f"  Found {len(packages)} packages in repository")

    # Step 2: Download required packages
    print("\n[2/4] Downloading required packages...")
    WORK_DIR.mkdir(parents=True, exist_ok=True)

    downloaded = {}
    for pkg_name in REQUIRED_PACKAGES:
        if pkg_name not in packages:
            print(f"  WARNING: {pkg_name} not found in repository, skipping")
            continue

        info = packages[pkg_name]
        filename = info["Filename"]
        version = info.get("Version", "?")
        url = f"{TERMUX_REPO}/{filename}"

        print(f"\n  {pkg_name} ({version})")
        deb_data = download(url, f"{pkg_name}.deb")

        pkg_dir = WORK_DIR / pkg_name
        if pkg_dir.exists():
            shutil.rmtree(pkg_dir)
        pkg_dir.mkdir()

        extract_deb(deb_data, pkg_dir)
        downloaded[pkg_name] = pkg_dir

    # Step 3: Collect node binary and libraries
    print("\n[3/4] Collecting binaries...")

    # Find node binary
    node_binary = None
    for pkg_dir in downloaded.values():
        for candidate in [
            pkg_dir / "data" / "data" / "com.termux" / "files" / "usr" / "bin" / "node",
            pkg_dir / "data/data/com.termux/files/usr/bin/node",
        ]:
            if candidate.exists():
                node_binary = candidate
                break
        # Also check relative paths from extraction
        for root, dirs, files in os.walk(pkg_dir):
            if "node" in files:
                candidate = Path(root) / "node"
                # Verify it's a binary, not a directory
                if candidate.is_file() and candidate.stat().st_size > 1024 * 1024:
                    node_binary = candidate
                    break

    if not node_binary:
        print("  ERROR: Node.js binary not found in extracted packages")
        sys.exit(1)

    size_mb = node_binary.stat().st_size / 1024 / 1024
    print(f"  Node.js binary: {node_binary} ({size_mb:.1f} MB)")

    # Collect all .so files
    all_libs = {}
    for pkg_dir in downloaded.values():
        for root, dirs, files in os.walk(pkg_dir):
            for f in files:
                if ".so" in f and should_include_lib(f):
                    full_path = Path(root) / f
                    if full_path.is_file():
                        all_libs[f] = full_path

    # Deduplicate: group by base name, pick the best version to include.
    # On Linux these would be symlinks; on Windows they're full copies.
    # For each group, we include ONE real file under the name the linker needs.
    from collections import defaultdict
    groups = defaultdict(list)
    for name in all_libs:
        base = name.split(".so")[0] + ".so"
        groups[base].append(name)

    libs_found = {}
    for base, names in groups.items():
        # Sort by version depth: libz.so < libz.so.1 < libz.so.1.3.2
        names.sort(key=lambda n: n.count("."))
        # Pick the source file (any will do, they're identical)
        src_path = all_libs[names[0]]

        if len(names) == 1:
            # Only one version (e.g., libc++_shared.so)
            libs_found[names[0]] = src_path
        else:
            # Multiple versions exist. Include:
            # 1. SONAME level (e.g., libz.so.1 or libicudata.so.78)
            # 2. Unversioned (e.g., libz.so) for any code that dlopen()s by short name
            soname = None
            for n in names:
                suffix = n[len(base):].strip(".")
                parts = suffix.split(".")
                if len(parts) == 1 and parts[0].isdigit():
                    soname = n
                    break
            if soname:
                libs_found[soname] = all_libs.get(soname, src_path)
            # Also include unversioned
            if base in all_libs:
                libs_found[base] = all_libs[base]
            elif soname:
                libs_found[base] = all_libs.get(soname, src_path)
            else:
                libs_found[names[0]] = src_path

    print(f"  Found {len(libs_found)} shared libraries (deduplicated)")

    # Step 4: Output
    print("\n[4/4] Creating output files...")

    # Place node binary
    JNILIBS_DIR.mkdir(parents=True, exist_ok=True)
    dest_node = JNILIBS_DIR / "libnode.so"
    shutil.copy2(node_binary, dest_node)
    print(f"  → {dest_node}")

    # Package nodelibs.zip
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    nodelibs_zip = ASSETS_DIR / "nodelibs.zip"

    with zipfile.ZipFile(nodelibs_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for name, path in sorted(libs_found.items()):
            zf.write(path, name)
            kb = path.stat().st_size / 1024
            print(f"  + {name} ({kb:.0f} KB)")

    zip_mb = nodelibs_zip.stat().st_size / 1024 / 1024
    print(f"  → {nodelibs_zip} ({zip_mb:.1f} MB)")

    print(f"\n{'=' * 60}")
    print("  Node.js + libraries ready!")
    print(f"{'=' * 60}")
    print(f"  {dest_node}")
    print(f"  {nodelibs_zip}")
    print(f"\n  Next: python scripts/package_sillytavern.py")
    print(f"  Then: Build APK in Android Studio")


if __name__ == "__main__":
    main()
