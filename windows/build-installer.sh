#!/usr/bin/env bash
# Build the Blocklight Windows setup executable:  out/Blocklight-Setup-1.0-x64.exe
# Run from anywhere; works on Linux, macOS and WSL (produces a Windows .exe installer).
#
# What it does:
#   1. downloads JavaFX Windows natives (Maven Central)           -> windows/tools/
#   2. downloads a Temurin Windows x64 JRE 25                     -> windows/tools/
#   3. fetches makensis + NSIS stubs (Debian packages, no root)   -> windows/tools/   [if not on PATH]
#   4. compiles the launcher with --release 21 and packs a Windows fat jar
#   5. stages jar + JRE and compiles the NSIS setup (asks for the Minecraft dir)
#
# Needs: curl, a JDK (21+), unzip (or bsdtar), bsdtar or ar (for .deb extraction).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
TOOLS="tools"
JFX_VERSION="21.0.5"
PRODUCT_VERSION="1.0"

mkdir -p "$TOOLS" ../out
TOOLS_ABS="$(cd "$TOOLS" && pwd)"

# ---------------------------------------------------------------- 1. JavaFX win
echo "==> JavaFX $JFX_VERSION windows jars"
for a in base controls graphics; do
    jar="$TOOLS/javafx-$a-$JFX_VERSION-win.jar"
    if [ ! -f "$jar" ]; then
        curl -fsSL -o "$jar.tmp" \
            "https://repo1.maven.org/maven2/org/openjfx/javafx-$a/$JFX_VERSION/javafx-$a-$JFX_VERSION-win.jar"
        mv "$jar.tmp" "$jar"
    fi
done

# ---------------------------------------------------------------- 2. Windows JRE
# Minecraft 26.x is compiled for Java 25 (class file 69) — the bundled runtime
# has to match it or the game dies with UnsupportedClassVersionError on start.
JRE_ZIP="$TOOLS/jre25-win-x64.zip"
echo "==> Temurin JRE 25 (windows x64)"
if [ ! -f "$JRE_ZIP" ]; then
    curl -fsSL -o "$JRE_ZIP.tmp" \
        "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jre/hotspot/normal/eclipse"
    mv "$JRE_ZIP.tmp" "$JRE_ZIP"
fi

# ---------------------------------------------------------------- 3. makensis
if command -v makensis >/dev/null 2>&1; then
    MAKENSIS="$(command -v makensis)"
    NSIS_DATA=""
else
    NSIS_ROOT="$TOOLS/nsis-deb"
    if [ ! -x "$NSIS_ROOT/usr/bin/makensis" ]; then
        echo "==> makensis not found — fetching Debian nsis packages (no root needed)"
        mkdir -p "$NSIS_ROOT"
        cd "$NSIS_ROOT"
        for pkg in nsis_3.12-1_amd64.deb nsis-common_3.12-1_all.deb; do
            [ -f "$pkg" ] || curl -fsSL -o "$pkg" "https://deb.debian.org/debian/pool/main/n/nsis/$pkg"
            extract() { # extract inner .deb regardless of tarball compression
                if command -v bsdtar >/dev/null 2>&1; then bsdtar xf "$1"
                else rm -rf _x && mkdir _x && cd _x && ar x "../$1" && tar -xf data.tar.* && cd ..
                fi
            }
            extract "$pkg"
        done
        cd - >/dev/null
    fi
    MAKENSIS="$NSIS_ROOT/usr/bin/makensis"
    NSIS_DATA="$NSIS_ROOT/usr/share/nsis"
fi

# ---------------------------------------------------------------- 4. windows jar
echo "==> Compiling (release 21) + packing Windows fat jar"
rm -rf "$ROOT/out/win-classes" "$ROOT/out/fat-win"
mkdir -p "$ROOT/out/win-classes"
# run from ROOT so source paths (project dir contains spaces) never word-split
(
    cd "$ROOT"
    javac --release 21 -d out/win-classes -cp "libs/*" $(find src/main/java -name '*.java')
    cp -r src/main/resources/. out/win-classes/
    mkdir -p out/fat-win
    cd out/fat-win
    for j in ../../libs/gson-*.jar "$TOOLS_ABS"/javafx-*-win.jar; do jar xf "$j"; done
    rm -f META-INF/*.SF META-INF/*.RSA META-INF/*.DSA 2>/dev/null || true
    cp -r ../win-classes/. .
    jar cfe ../blocklight-win.jar dev.mclauncher.MainLauncher .
    cd ../..
)
mv "$ROOT/out/blocklight-win.jar" Blocklight.jar

# ---------------------------------------------------------------- 5. staging + setup
echo "==> Staging (jar + Windows JRE)"
rm -rf staging/runtime
mv -f Blocklight.jar staging/Blocklight.jar 2>/dev/null || { mkdir -p staging && mv -f Blocklight.jar staging/Blocklight.jar; }
echo "    unpacking JRE..."
rm -rf "$TOOLS/jre-unpack" && mkdir -p "$TOOLS/jre-unpack"
unzip -q "$JRE_ZIP" -d "$TOOLS/jre-unpack"
JRE_TOP="$(find "$TOOLS/jre-unpack" -maxdepth 1 -mindepth 1 -type d | head -1)"
mv "$JRE_TOP" staging/runtime
rmdir "$TOOLS/jre-unpack"

echo "==> Compiling installer with makensis"
if [ -n "$NSIS_DATA" ]; then NSISDIR="$NSIS_DATA" "$MAKENSIS" Blocklight.nsi
else "$MAKENSIS" Blocklight.nsi
fi

SETUP="../out/Blocklight-Setup-$PRODUCT_VERSION-x64.exe"
echo ""
echo "Built: $SETUP ($(du -h "$SETUP" | cut -f1))"
