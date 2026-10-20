#!/usr/bin/env bash
# Run Blocklight. Builds first if the jar is missing.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f out/blocklight.jar ]; then
    ./build.sh
fi

exec java --enable-native-access=ALL-UNNAMED -jar out/blocklight.jar "$@"
