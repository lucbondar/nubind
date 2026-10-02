#!/system/bin/sh
MODDIR=${0%/*}

# Directorios de trabajo
mkdir -p "$MODDIR/config"
touch "$MODDIR/status.json"
echo '{"mounted":false}' > "$MODDIR/status.json"
