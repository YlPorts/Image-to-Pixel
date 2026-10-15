#!/usr/bin/env bash
set -euo pipefail
mkdir -p _android-test
logcat_pid=''
collect() {
  if [ -n "$logcat_pid" ]; then kill "$logcat_pid" 2>/dev/null || true; fi
  adb pull /sdcard/Android/data/com.ylports.imagetopixel/files/test-results _android-test/device 2>/dev/null || true
  free -m > _android-test/host-memory.txt
  df -h > _android-test/host-disk.txt
  sudo dmesg | tail -80 > _android-test/host-kernel.log || true
}
trap collect EXIT
adb install -r android-artifacts/Image-to-Pixel-1.0.0-debug.apk
adb install -r android-artifacts/Image-to-Pixel-tests.apk
adb shell svc wifi disable
adb shell svc data disable
adb shell cmd connectivity airplane-mode enable
adb logcat -c
adb logcat -v threadtime > _android-test/logcat.txt 2>&1 &
logcat_pid=$!
set +e
adb shell am instrument -w -r com.ylports.imagetopixel.test/androidx.test.runner.AndroidJUnitRunner | tee _android-test/instrumentation.log
instrumentation_status=${PIPESTATUS[0]}
set -e
if [ "$instrumentation_status" -ne 0 ]; then exit "$instrumentation_status"; fi
if ! grep -q 'OK (' _android-test/instrumentation.log || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' _android-test/instrumentation.log; then
  exit 1
fi
