#!/bin/sh
# Build, push and start the AirMate host, replacing whatever was running.
#
# Arguments after the serial go straight to the server: size=WxH dpi=N freeform=true|false
# hevc=true|false flags=0x... bg=RRGGBB
set -e
cd "$(dirname "$0")"
SERIAL="$(adb devices | grep '_adb-tls-connect' | awk '{print $1}' | head -1)"
[ -n "$SERIAL" ] || SERIAL="$(adb devices | awk 'NR==2{print $1}')"
[ -n "$SERIAL" ] || { echo "no device" >&2; exit 1; }

./build.sh > /dev/null
./stop.sh "$SERIAL" > /dev/null 2>&1 || true
adb -s "$SERIAL" push airmate-server.jar /data/local/tmp/ > /dev/null
echo "starting on $SERIAL: ${*:-size=1808x1088 dpi=160}"
exec adb -s "$SERIAL" shell \
    "CLASSPATH=/data/local/tmp/airmate-server.jar app_process / com.airmate.host.server.Server ${*:-size=1808x1088 dpi=160}"
