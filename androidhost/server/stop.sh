#!/bin/sh
# Kill every AirMate host process on the device, and with it every display it was holding.
#
# Matched on the command line rather than the process name: everything launched through
# `app_process` is called app_process, so `pkill -f airmate` matches nothing and the leaked
# processes stay, each holding a virtual display and one of the device's few hardware encoders.
set -e
SERIAL="${1:-$(adb devices | grep '_adb-tls-connect' | awk '{print $1}' | head -1)}"
[ -n "$SERIAL" ] || SERIAL="$(adb devices | awk 'NR==2{print $1}')"

PIDS=$(adb -s "$SERIAL" shell "ps -A | grep app_process | grep -v grep" 2>/dev/null \
       | awk '$1=="shell"{print $2}' | tr -d '\r')
for PID in $PIDS; do
    if adb -s "$SERIAL" shell "tr '\0' ' ' < /proc/$PID/cmdline" 2>/dev/null | grep -q com.airmate.host; then
        echo "killing $PID"
        adb -s "$SERIAL" shell "kill -9 $PID" 2>/dev/null || true
    fi
done
echo "AirMate displays left: $(adb -s "$SERIAL" shell 'dumpsys display' 2>/dev/null | grep -c 'DisplayDeviceInfo{"AirMate"')"
