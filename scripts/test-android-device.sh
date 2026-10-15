#!/usr/bin/env bash
set -euo pipefail
mkdir -p _android-test
adb install -r android-artifacts/Image-to-Pixel-1.0.0-debug.apk
adb install -r android-artifacts/Image-to-Pixel-tests.apk
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true || true
adb logcat -c
adb shell am instrument -w -r com.ylports.imagetopixel.test/androidx.test.runner.AndroidJUnitRunner | tee _android-test/instrumentation.log
adb logcat -d > _android-test/logcat.txt
adb pull /sdcard/Android/data/com.ylports.imagetopixel/files/test-results _android-test/device || true
if ! grep -q 'OK (' _android-test/instrumentation.log || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' _android-test/instrumentation.log; then
  exit 1
fi
