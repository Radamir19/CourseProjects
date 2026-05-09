#!/usr/bin/env bash
cd "/Users/radamirnurmagomedov/Downloads/CourseProjects-main"
clear
echo "=== JBlockStorage узел C (порт 8083, seed=A,B) ==="
export JBS_HOME="$HOME/.jblockstorage-C"
export JBS_PORT=8083
export JBS_SEEDS="127.0.0.1:8081,127.0.0.1:8082"
./gradlew run --console=plain
