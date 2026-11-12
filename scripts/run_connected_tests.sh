#!/usr/bin/env bash
set -u

test_status=0
./gradlew :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest --no-daemon || test_status=$?
mkdir -p artifacts
for name in group-table group-completed group-large-font group-inline-large-font groups-list orders-list orders-compact-large-font order-editor-flags order-editor-compact order-editor-summary order-delete-button price-history group-timeout; do
    adb pull "/sdcard/Download/OrdersTest/$name.png" "artifacts/$name.png" || true
done
if [ "$test_status" -ne 0 ]; then
    adb logcat -d -v threadtime > artifacts/android-test-logcat.txt
fi
exit "$test_status"
