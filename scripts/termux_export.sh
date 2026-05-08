#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# Run this script in Termux to export Node.js + libraries.
# Everything is packed into a single zip for easy transfer.
#
# Usage:
#   bash termux_export.sh
#
# Output:
#   ~/storage/downloads/node-android-arm64.zip
# ============================================================

set -e

echo "=== SillyTavern Android: Termux Export ==="
echo ""

# Ensure storage access
if [ ! -d ~/storage/downloads ]; then
    echo "Requesting storage permission..."
    termux-setup-storage
    sleep 2
fi

# Ensure Node.js is installed
if ! command -v node &> /dev/null; then
    echo "Installing Node.js..."
    pkg install -y nodejs
fi

NODE_VERSION=$(node --version)
echo "Node.js version: $NODE_VERSION"

# Create temp workspace
WORKDIR=$(mktemp -d)
mkdir -p "$WORKDIR/libs"

# Copy node binary
echo "Copying node binary..."
cp "$(which node)" "$WORKDIR/node"

# Copy required shared libraries
echo "Copying shared libraries..."
LIB_DIR="/data/data/com.termux/files/usr/lib"

libs=(
    "libc++_shared.so"
    "libandroid-posix-semaphore.so"
    "libcares.so"
    "libnghttp2.so"
    "libnghttp3.so"
    "libngtcp2.so"
    "libngtcp2_crypto_ossl.so"
    "libbrotlicommon.so"
    "libbrotlidec.so"
    "libbrotlienc.so"
    "libsqlite3.so"
)

# Versioned libs (copy with version suffix)
versioned_libs=(
    "libz.so.1"
    "libcrypto.so.3"
    "libssl.so.3"
    "libsqlite3.so.0"
)

# ICU libraries (glob)
icu_libs=(
    "libicudata.so"
    "libicui18n.so"
    "libicuuc.so"
)

for lib in "${libs[@]}"; do
    if [ -f "$LIB_DIR/$lib" ]; then
        cp "$LIB_DIR/$lib" "$WORKDIR/libs/"
    else
        echo "  WARNING: $lib not found"
    fi
done

for lib in "${versioned_libs[@]}"; do
    if [ -f "$LIB_DIR/$lib" ]; then
        cp "$LIB_DIR/$lib" "$WORKDIR/libs/"
    else
        echo "  WARNING: $lib not found"
    fi
done

for lib in "${icu_libs[@]}"; do
    # Copy base .so and first versioned .so.XX
    if [ -f "$LIB_DIR/$lib" ]; then
        cp "$LIB_DIR/$lib" "$WORKDIR/libs/"
    fi
    # Also copy .so.XX version
    for f in "$LIB_DIR/${lib}."[0-9]*; do
        if [ -f "$f" ] && [[ "$f" != *"."*"."*"."* ]]; then
            cp "$f" "$WORKDIR/libs/"
        fi
    done
done

# Pack everything
OUTPUT=~/storage/downloads/node-android-arm64.zip
echo ""
echo "Packaging..."
cd "$WORKDIR"
zip -r "$OUTPUT" node libs/

# Summary
echo ""
echo "=== Done! ==="
echo "Output: $OUTPUT"
echo "Node: $NODE_VERSION"
echo "Libraries: $(ls libs/ | wc -l) files"
echo ""
echo "Transfer this file to your PC and run:"
echo "  python scripts/setup_assets.py path/to/node-android-arm64.zip"

# Cleanup
rm -rf "$WORKDIR"
