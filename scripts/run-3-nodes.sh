#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

mkdir -p "$HOME/.jblockstorage-A" "$HOME/.jblockstorage-B" "$HOME/.jblockstorage-C"

# Создаём 3 launcher-скрипта, по одному на узел.
LAUNCH_DIR="$PROJECT_ROOT/scripts/.launchers"
mkdir -p "$LAUNCH_DIR"

cat > "$LAUNCH_DIR/node-A.command" <<EOF
#!/usr/bin/env bash
cd "$PROJECT_ROOT"
clear
echo "=== JBlockStorage узел A (порт 8081) ==="
export JBS_HOME="\$HOME/.jblockstorage-A"
export JBS_PORT=8081
export JBS_SEEDS=""
./gradlew run --console=plain
EOF

cat > "$LAUNCH_DIR/node-B.command" <<EOF
#!/usr/bin/env bash
cd "$PROJECT_ROOT"
clear
echo "=== JBlockStorage узел B (порт 8082, seed=A) ==="
export JBS_HOME="\$HOME/.jblockstorage-B"
export JBS_PORT=8082
export JBS_SEEDS="127.0.0.1:8081"
./gradlew run --console=plain
EOF

cat > "$LAUNCH_DIR/node-C.command" <<EOF
#!/usr/bin/env bash
cd "$PROJECT_ROOT"
clear
echo "=== JBlockStorage узел C (порт 8083, seed=A,B) ==="
export JBS_HOME="\$HOME/.jblockstorage-C"
export JBS_PORT=8083
export JBS_SEEDS="127.0.0.1:8081,127.0.0.1:8082"
./gradlew run --console=plain
EOF

chmod +x "$LAUNCH_DIR"/*.command

echo "Открываю узел A..."
open -a Terminal "$LAUNCH_DIR/node-A.command"
sleep 2
echo "Открываю узел B..."
open -a Terminal "$LAUNCH_DIR/node-B.command"
sleep 2
echo "Открываю узел C..."
open -a Terminal "$LAUNCH_DIR/node-C.command"

echo "Готово. Проверь, что открылись 3 окна Terminal."
