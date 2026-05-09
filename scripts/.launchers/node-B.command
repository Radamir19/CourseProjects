#!/usr/bin/env bash
cd "/Users/radamirnurmagomedov/Downloads/CourseProjects-main"
clear
echo "=== JBlockStorage узел B (порт 8082, seed=A) ==="
export JBS_HOME="$HOME/.jblockstorage-B"
export JBS_PORT=8082
export JBS_SEEDS="127.0.0.1:8081"
./gradlew run --console=plain
