#!/bin/bash
set -e

RECORD_DIR="/home/miro/v2x2map/recordings"
mkdir -p "$RECORD_DIR"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
PCAP_FILE="$RECORD_DIR/fahrt-$TIMESTAMP.pcap"

source /home/miro/venv/bin/activate

exec python3 /home/miro/V2X2MAP/bridge/its_g5_bridge.py \
  --node-id V2X2MAP:3844beaa0c04 \
  --dashboard-port 8080 \
  --no-browser \
  --mqtt-autostart \
  --pcap-out "$PCAP_FILE"
