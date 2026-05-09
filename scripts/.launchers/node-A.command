#!/usr/bin/env bash
cd "/Users/radamirnurmagomedov/Downloads/CourseProjects-main"
clear
echo "=== JBlockStorage узел A (порт 8081) ==="
export JBS_HOME="$HOME/.jblockstorage-A"
export JBS_PORT=8081
export JBS_SEEDS=""
./gradlew run --console=plain
