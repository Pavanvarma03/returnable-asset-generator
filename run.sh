#!/usr/bin/env bash
# Usage: ./run.sh [--seed N] [--assets N] [--shipments N] [--events N] [--as-of YYYY-MM-DD] [--out DIR] [--sql false]
# Defaults: full dataset, seed 20261022 -> ./out
set -euo pipefail
cd "$(dirname "$0")"
[ -d build ] || ./build.sh
java -cp build assetgen.Main "$@"
