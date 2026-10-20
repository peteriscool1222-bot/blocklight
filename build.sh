#!/usr/bin/env bash
# Build Blocklight into a runnable fat jar (out/blocklight.jar).
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out/classes

echo "Compiling..."
javac -d out/classes -cp "libs/*" $(find src/main/java -name '*.java')

cp -r src/main/resources/. out/classes/

echo "Packing fat jar..."
rm -rf out/fat
mkdir -p out/fat
cd out/fat
for jar in ../..//libs/*.jar; do
    jar xf "../../libs/$(basename "$jar")"
done
rm -rf META-INF/*.SF META-INF/*.RSA META-INF/*.DSA 2>/dev/null || true
cp -r ../classes/. .
jar cfe ../blocklight.jar dev.mclauncher.MainLauncher .
cd ../..

echo "Built: out/blocklight.jar"
