#!/bin/sh
# Pure Java lossless-survey regression; no Minecraft runtime or world edits.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$out" "$root/mod/src/main/java/dev/agentcraft/hq/SurveyCuboids.java" "$root/tools/java/SurveyCuboidsTest.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -ea -cp "$out" dev.agentcraft.hq.SurveyCuboidsTest
