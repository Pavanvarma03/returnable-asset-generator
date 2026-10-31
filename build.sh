#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
mkdir -p build
javac -d build src/main/java/assetgen/*.java
