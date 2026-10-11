#!/usr/bin/env bash
# Opens an anchored old map in each hunt-map view on an emulator and records whether the app survives.
set -u
P=com.brandonthomas.oldground
OUT=smoke
mkdir -p $OUT
adb install -r -g oldground.apk > $OUT/install.txt 2>&1 || { echo "INSTALL FAILED"; cat $OUT/install.txt; }
for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $P android.permission.$perm 2>/dev/null; done
adb emu geo fix -82.99 39.96 || true
# Seed one anchored map.
adb shell "run-as $P sh -c 'mkdir -p files/oldground/maps/ci1'"
adb shell "run-as $P sh -c 'cat > files/oldground/maps/ci1/original.jpg'" < ci/testmap.jpg
adb shell "run-as $P sh -c 'cat > files/oldground/data.json'" < ci/data.json
adb shell "run-as $P ls -l files/oldground files/oldground/maps/ci1"
adb logcat -c
: > $OUT/result.txt
for mode in HOME OVERLAY SWIPE SPLIT DRIVE; do
  adb shell am force-stop $P
  sleep 1
  if [ $mode = HOME ]; then adb shell am start -W -n $P/.MainActivity > /dev/null
  else adb shell am start -W -n $P/.MainActivity --es openMap ci1 --es mode $mode > /dev/null; fi
  sleep 30
  if adb shell pidof $P > /dev/null; then echo "$mode: running" >> $OUT/result.txt
  else echo "$mode: CRASHED" >> $OUT/result.txt; fi
  adb exec-out screencap -p > $OUT/$mode.png
done
adb shell "run-as $P cat files/last-crash.txt" > $OUT/last-crash.txt 2>/dev/null || true
adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d -v brief AndroidRuntime:E OldGround:V libc:F DEBUG:F maplibre:E Mbgl:E '*:S' > $OUT/errors.txt
cat $OUT/result.txt
