#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
[ -d build ] || ./build.sh
java -cp build assetgen.Main "$@"
