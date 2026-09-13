#!/usr/bin/env bash
# fetch-natives: the binaries the app bundles as lib/arm64-v8a/lib*.so —
# Android runs an app's own native-library files, and packaging requires
# the lib*.so name.
#   libunidatum.so   the engine (android/arm64 release archive; needs gh auth to the release repo)
#   libduckdb.so     DuckDB's musl arm64 CLI, DT_NEEDED rewritten to the names below
#   libmusl.so       musl's loader (ld-musl-aarch64.so.1), which runs libduckdb.so
#   libstdcpp6.so, libgccs1.so   Alpine's libstdc++ and libgcc
#   libduckwrap.so   a shell script: the engine's P2PFS_DUCKDB; runs the CLI through the loader
# Usage: tools/fetch-natives.sh [unidatum version, default v2.367.0]
set -euo pipefail
V="${1:-v2.367.0}"; REPO="${UNIDATUM_REPO:-OptimalMatch/peer-to-peer-db}"
DUCKDB_VERSION="${DUCKDB_VERSION:-v1.5.5}"; ALPINE="${ALPINE:-v3.21}"
cd "$(dirname "$0")/.."
J=app/src/main/jniLibs/arm64-v8a; mkdir -p "$J" tools/natives
command -v patchelf >/dev/null || { echo "patchelf is required" >&2; exit 1; }
if [ ! -f "tools/natives/unidatum-$V-android-arm64.tar.gz" ]; then
  gh release download "$V" -R "$REPO" -p "unidatum-$V-android-arm64.tar.gz" -D tools/natives --clobber
fi
tar xzf "tools/natives/unidatum-$V-android-arm64.tar.gz" -C tools/natives --strip-components=1 "unidatum-$V-android-arm64/unidatum"
cp tools/natives/unidatum "$J/libunidatum.so"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
curl -sSL -o "$tmp/duckdb.gz" "https://github.com/duckdb/duckdb/releases/download/$DUCKDB_VERSION/duckdb_cli-linux-arm64-musl.gz"; gunzip -f "$tmp/duckdb.gz"
base="https://dl-cdn.alpinelinux.org/alpine/$ALPINE/main/aarch64"; idx=$(curl -sSL "$base/")
for pkg in musl-1 libstdc++-1 libgcc-1; do
  a=$(echo "$idx" | grep -o "${pkg}[^\"]*\.apk" | grep -vE 'dev|utils|dbg' | head -1)
  curl -sSL -o "$tmp/$a" "$base/$a"; tar xzf "$tmp/$a" -C "$tmp" 2>/dev/null || true
done
cp "$tmp/lib/ld-musl-aarch64.so.1" "$J/libmusl.so"
cp "$(ls "$tmp"/usr/lib/libstdc++.so.6.* | head -1)" "$J/libstdcpp6.so"
cp "$tmp/usr/lib/libgcc_s.so.1" "$J/libgccs1.so"
cp "$tmp/duckdb" "$J/libduckdb.so"
patchelf --set-interpreter /nonexistent/libmusl.so --remove-rpath \
  --replace-needed libstdc++.so.6 libstdcpp6.so --replace-needed libgcc_s.so.1 libgccs1.so \
  --replace-needed libc.musl-aarch64.so.1 libmusl.so "$J/libduckdb.so"
# The transitive names too: libstdc++ needs libgcc_s and musl's libc; the loader looks them up by these names in LD_LIBRARY_PATH.
for f in libstdcpp6.so libgccs1.so; do
  patchelf --replace-needed libgcc_s.so.1 libgccs1.so --replace-needed libc.musl-aarch64.so.1 libmusl.so "$J/$f"
done
cat > "$J/libduckwrap.so" <<'SH'
#!/system/bin/sh
D=$(dirname "$0")
export LD_LIBRARY_PATH="$D"
exec "$D/libmusl.so" "$D/libduckdb.so" "$@"
SH
chmod 755 "$J"/*.so
echo "natives in $J:"; ls -l "$J" | sed 's/^/  /'
