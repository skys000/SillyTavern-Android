#!/usr/bin/env python3
"""
Download SillyTavern from GitHub and package it for the Android app.

Usage:
    python scripts/package_sillytavern.py [--branch release] [--repo SillyTavern/SillyTavern]

Output:
    app/src/main/assets/sillytavern.zip
"""

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
WORK_DIR = PROJECT_ROOT / "build_workspace"
OUTPUT_ZIP = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "sillytavern.zip"


def run(cmd, cwd=None):
    print(f"  $ {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"ERROR: {result.stderr}")
        sys.exit(1)
    return result.stdout


def clone_sillytavern(repo: str, branch: str):
    st_dir = WORK_DIR / "SillyTavern"
    if st_dir.exists():
        print(f"Removing old {st_dir}")
        shutil.rmtree(st_dir)

    WORK_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Cloning {repo} (branch: {branch})...")
    run([
        "git", "clone",
        "--depth", "1",
        "--branch", branch,
        f"https://github.com/{repo}.git",
        str(st_dir)
    ])
    return st_dir


def install_deps(st_dir: Path):
    print("Installing production dependencies...")
    # Use npm ci if package-lock.json exists, otherwise npm install
    # --ignore-scripts: skip native module compilation (wrong platform: Windows vs Android ARM64)
    npm_cmd = "npm.cmd" if sys.platform == "win32" else "npm"
    lock_file = st_dir / "package-lock.json"
    if lock_file.exists():
        run([npm_cmd, "ci", "--omit=dev", "--ignore-scripts"], cwd=str(st_dir))
    else:
        run([npm_cmd, "install", "--omit=dev", "--ignore-scripts"], cwd=str(st_dir))


def package_zip(st_dir: Path):
    print(f"Packaging to {OUTPUT_ZIP}...")
    OUTPUT_ZIP.parent.mkdir(parents=True, exist_ok=True)

    # Directories/files to exclude from the zip
    exclude = {".git", ".github", ".vscode", ".gemini", "node_modules/.cache"}

    count = 0
    with zipfile.ZipFile(OUTPUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
        for root, dirs, files in os.walk(st_dir):
            # Skip excluded directories
            rel_root = os.path.relpath(root, st_dir)
            dirs[:] = [d for d in dirs if d not in exclude
                       and os.path.join(rel_root, d).replace("\\", "/") not in exclude]

            for f in files:
                full_path = os.path.join(root, f)
                arc_name = os.path.relpath(full_path, st_dir).replace("\\", "/")
                zf.write(full_path, arc_name)
                count += 1
                if count % 2000 == 0:
                    print(f"  {count} files...")

    size_mb = OUTPUT_ZIP.stat().st_size / 1024 / 1024
    print(f"Done: {count} files, {size_mb:.1f} MB")


def main():
    parser = argparse.ArgumentParser(description="Package SillyTavern for Android")
    parser.add_argument("--repo", default="SillyTavern/SillyTavern",
                        help="GitHub repo (default: SillyTavern/SillyTavern)")
    parser.add_argument("--branch", default="release",
                        help="Git branch (default: release)")
    parser.add_argument("--local", type=str, default=None,
                        help="Use local SillyTavern directory instead of cloning")
    args = parser.parse_args()

    if args.local:
        st_dir = Path(args.local).resolve()
        if not (st_dir / "server.js").exists():
            print(f"ERROR: {st_dir}/server.js not found")
            sys.exit(1)
        print(f"Using local SillyTavern: {st_dir}")
    else:
        st_dir = clone_sillytavern(args.repo, args.branch)
        install_deps(st_dir)

    package_zip(st_dir)
    print("\nSuccess! Now build the APK in Android Studio.")


if __name__ == "__main__":
    main()
