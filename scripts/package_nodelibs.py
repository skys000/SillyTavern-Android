#!/usr/bin/env python3
"""
Package Node.js shared libraries for the Android app.

Usage:
    python scripts/package_nodelibs.py <path_to_nodelibs_folder>

The folder should contain .so files extracted from Termux's /data/data/com.termux/files/usr/lib/.

Output:
    app/src/main/assets/nodelibs.zip
"""

import os
import sys
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_ZIP = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "nodelibs.zip"

# Libraries required by Node.js from Termux
REQUIRED_PATTERNS = [
    "libz.so",
    "libc++_shared.so",
    "libcares.so",
    "libnghttp2.so",
    "libnghttp3.so",
    "libngtcp2",
    "libssl.so",
    "libcrypto.so",
    "libbrotli",
    "libicu",
    "libsqlite3.so",
    "libandroid-posix-semaphore.so",
]

# Skip these (test/tool libraries not needed at runtime)
SKIP_PATTERNS = ["libicutest", "libicutu", "libicuio"]


def should_include(filename: str) -> bool:
    for skip in SKIP_PATTERNS:
        if filename.startswith(skip):
            return False
    for pattern in REQUIRED_PATTERNS:
        if filename.startswith(pattern.rstrip(".so").rstrip("*")):
            return True
    return False


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <path_to_nodelibs_folder>")
        print(f"\nExample: {sys.argv[0]} ~/Downloads/nodelibs")
        sys.exit(1)

    src_dir = Path(sys.argv[1]).resolve()
    if not src_dir.is_dir():
        print(f"ERROR: {src_dir} is not a directory")
        sys.exit(1)

    # Find all .so files
    so_files = sorted(f for f in src_dir.iterdir()
                      if f.is_file() and ".so" in f.name)

    if not so_files:
        print(f"ERROR: No .so files found in {src_dir}")
        sys.exit(1)

    # Filter to required libraries
    included = [f for f in so_files if should_include(f.name)]

    # Skip overly-versioned duplicates (e.g., keep libz.so.1 but skip libz.so.1.3.2)
    filtered = []
    for f in included:
        # Count dots after .so
        parts_after_so = f.name.split(".so")
        if len(parts_after_so) > 1:
            version_parts = parts_after_so[1].strip(".").split(".")
            if len(version_parts) > 2:  # e.g., "1.3.2" -> skip
                continue
        filtered.append(f)

    print(f"Source: {src_dir}")
    print(f"Found {len(so_files)} .so files, including {len(filtered)} needed libraries")
    print()

    OUTPUT_ZIP.parent.mkdir(parents=True, exist_ok=True)

    total_size = 0
    with zipfile.ZipFile(OUTPUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for f in filtered:
            zf.write(f, f.name)
            size_kb = f.stat().st_size / 1024
            total_size += f.stat().st_size
            print(f"  + {f.name} ({size_kb:.0f} KB)")

    zip_size_mb = OUTPUT_ZIP.stat().st_size / 1024 / 1024
    total_mb = total_size / 1024 / 1024
    print(f"\nPackaged {len(filtered)} libraries ({total_mb:.1f} MB uncompressed)")
    print(f"Output: {OUTPUT_ZIP} ({zip_size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
