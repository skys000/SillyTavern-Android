#!/usr/bin/env python3
"""
One-command setup: prepares ALL assets needed to build the APK.

Usage:
    python scripts/setup_assets.py                              # Auto-download everything
    python scripts/setup_assets.py --from-termux node.zip       # Use Termux export zip
    python scripts/setup_assets.py --st-branch staging           # Use different ST branch

This script:
  1. Downloads Node.js binary + libs from Termux repo (or extracts from zip)
  2. Packages shared libraries → app/src/main/assets/nodelibs.zip
  3. Clones SillyTavern from GitHub, installs deps → app/src/main/assets/sillytavern.zip

After running this, open the project in Android Studio and build the APK.
"""

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
JNILIBS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
WORK_DIR = PROJECT_ROOT / "build_workspace"


def step(msg):
    print(f"\n{'='*60}\n  {msg}\n{'='*60}")


def run(cmd, cwd=None):
    print(f"  $ {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  STDOUT: {result.stdout[:500]}")
        print(f"  STDERR: {result.stderr[:500]}")
        print("  FAILED!")
        sys.exit(1)
    return result.stdout


def create_stub_packages(nm_dir: Path):
    """Replace native packages with JS stubs that won't crash on import."""
    stubs = {
        "tiktoken": {
            "package.json": '{"name":"tiktoken","version":"0.0.0-stub","main":"index.js","type":"commonjs"}',
            "index.js": (
                "// Stub: native tiktoken not available on Android\n"
                "module.exports = {\n"
                "  get_encoding: () => ({ encode: (t) => [], decode: (t) => '', free: () => {} }),\n"
                "  encoding_for_model: () => ({ encode: (t) => [], decode: (t) => '', free: () => {} }),\n"
                "};\n"
            ),
        },
        "better-sqlite3": {
            "package.json": '{"name":"better-sqlite3","version":"0.0.0-stub","main":"index.js","type":"commonjs"}',
            "index.js": (
                "// Stub: native better-sqlite3 not available on Android\n"
                "class Database { constructor() { throw new Error('SQLite native module not available'); } }\n"
                "module.exports = Database;\n"
            ),
        },
    }
    for pkg_name, files in stubs.items():
        pkg_dir = nm_dir / pkg_name
        if pkg_dir.exists():
            shutil.rmtree(pkg_dir)
        pkg_dir.mkdir(parents=True, exist_ok=True)
        for filename, content in files.items():
            (pkg_dir / filename).write_text(content, encoding="utf-8")
        print(f"  Stubbed native package: {pkg_name}")


def download_node_from_termux():
    """Download Node.js + libs from Termux repo (no phone needed)."""
    step("Step 1/2: Downloading Node.js from Termux repository")
    # Delegate to download_node.py
    script = PROJECT_ROOT / "scripts" / "download_node.py"
    result = subprocess.run([sys.executable, str(script)], cwd=str(PROJECT_ROOT))
    if result.returncode != 0:
        print("  FAILED! See errors above.")
        sys.exit(1)


def extract_node_zip(zip_path: Path):
    """Extract node binary and libs from a Termux export zip."""
    step("Step 1/2: Extracting Node.js from Termux export")

    temp_dir = WORK_DIR / "node_extract"
    if temp_dir.exists():
        shutil.rmtree(temp_dir)
    temp_dir.mkdir(parents=True)

    print(f"  Extracting {zip_path}...")
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(temp_dir)

    # Place node binary
    node_bin = temp_dir / "node"
    if not node_bin.exists():
        print("  ERROR: 'node' not found in zip root")
        sys.exit(1)

    JNILIBS_DIR.mkdir(parents=True, exist_ok=True)
    dest = JNILIBS_DIR / "libnode.so"
    shutil.copy2(node_bin, dest)
    size_mb = dest.stat().st_size / 1024 / 1024
    print(f"  → {dest} ({size_mb:.1f} MB)")

    # Package node libs
    libs_dir = temp_dir / "libs"
    if not libs_dir.exists() or not any(libs_dir.iterdir()):
        print("  ERROR: 'libs/' directory not found or empty in zip")
        sys.exit(1)

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    nodelibs_zip = ASSETS_DIR / "nodelibs.zip"

    count = 0
    with zipfile.ZipFile(nodelibs_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for f in sorted(libs_dir.iterdir()):
            if f.is_file() and ".so" in f.name:
                zf.write(f, f.name)
                count += 1
                print(f"  + {f.name}")

    size_mb = nodelibs_zip.stat().st_size / 1024 / 1024
    print(f"  → {nodelibs_zip} ({count} libs, {size_mb:.1f} MB)")


def package_sillytavern(branch: str, repo: str):
    """Clone SillyTavern and package it."""
    step("Step 2/2: Downloading and packaging SillyTavern")

    st_dir = WORK_DIR / "SillyTavern"
    if st_dir.exists():
        print(f"  Removing old {st_dir.name}...")
        shutil.rmtree(st_dir)

    WORK_DIR.mkdir(parents=True, exist_ok=True)

    print(f"  Cloning {repo} (branch: {branch})...")
    run(["git", "clone", "--depth", "1", "--branch", branch,
         f"https://github.com/{repo}.git", str(st_dir)])

    print("  Installing dependencies...")
    npm_cmd = "npm.cmd" if sys.platform == "win32" else "npm"
    lock_file = st_dir / "package-lock.json"
    # --ignore-scripts: skip native module compilation (wrong platform: Windows vs Android ARM64)
    if lock_file.exists():
        run([npm_cmd, "ci", "--omit=dev", "--ignore-scripts"], cwd=str(st_dir))
    else:
        run([npm_cmd, "install", "--omit=dev", "--ignore-scripts"], cwd=str(st_dir))

    # Replace native packages with stubs. These packages require native compilation
    # which can't cross-compile from Windows to Android ARM64.
    # SillyTavern will gracefully degrade when these return empty results.
    create_stub_packages(st_dir / "node_modules")

    print("  Packaging zip...")
    output_zip = ASSETS_DIR / "sillytavern.zip"
    exclude_dirs = {".git", ".github", ".vscode", ".gemini"}

    count = 0
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
        for root, dirs, files in os.walk(st_dir):
            dirs[:] = [d for d in dirs if d not in exclude_dirs]
            for f in files:
                full = os.path.join(root, f)
                arc = os.path.relpath(full, st_dir).replace("\\", "/")
                zf.write(full, arc)
                count += 1
                if count % 3000 == 0:
                    print(f"    {count} files...")

    size_mb = output_zip.stat().st_size / 1024 / 1024
    print(f"  → {output_zip} ({count} files, {size_mb:.1f} MB)")


def main():
    parser = argparse.ArgumentParser(
        description="Prepare all assets for SillyTavern Android APK build")
    parser.add_argument("--from-termux", type=str, default=None,
                        help="Path to node-android-arm64.zip from Termux export (optional)")
    parser.add_argument("--st-branch", default="release",
                        help="SillyTavern git branch (default: release)")
    parser.add_argument("--st-repo", default="SillyTavern/SillyTavern",
                        help="SillyTavern GitHub repo")
    args = parser.parse_args()

    if args.from_termux:
        node_zip = Path(args.from_termux).resolve()
        if not node_zip.exists():
            print(f"ERROR: File not found: {node_zip}")
            sys.exit(1)
        extract_node_zip(node_zip)
    else:
        download_node_from_termux()

    package_sillytavern(args.st_branch, args.st_repo)

    print(f"\n{'='*60}")
    print("  ALL DONE!")
    print(f"{'='*60}")
    print(f"\n  Assets ready at:")
    print(f"    {JNILIBS_DIR / 'libnode.so'}")
    print(f"    {ASSETS_DIR / 'nodelibs.zip'}")
    print(f"    {ASSETS_DIR / 'sillytavern.zip'}")
    print(f"\n  Next: Open project in Android Studio → Build → APK")
    print()


if __name__ == "__main__":
    main()
