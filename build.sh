#!/usr/bin/env bash
# Compile to ./build
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
javac -d build src/main/java/assetgen/*.java
