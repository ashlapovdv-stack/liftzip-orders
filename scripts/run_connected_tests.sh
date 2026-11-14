#!/usr/bin/env bash
set -u

test_status=0
./gradlew :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest --no-daemon || test_status=$?
mkdir -p artifacts
for name in group-table group-completed group-large-font group-inline-large-font groups-list orders-list orders-compact-large-font order-editor-flags order-editor-compact order-editor-summary order-delete-button position-editor formula-order formula-group price-history group-timeout; do
    adb pull "/sdcard/Download/OrdersTest/$name.png" "artifacts/$name.png" || true
done
# Explicitly run the opt-in Taobao network diagnostic only in its isolated PR.
# Instrumentation APK is installed by the connected tests above.
if [ "${GITHUB_HEAD_REF:-}" = "diagnostics/taobao-short-link" ]; then
    echo "Running Taobao short-link diagnostic on Android emulator"
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    adb shell am instrument -w \
        -e class ru.liftzip.orders.app.TaobaoShortLinkDiagnosticTest \
        -e taobaoDiagnostic true \
        ru.liftzip.orders.test/androidx.test.runner.AndroidJUnitRunner \
        | tee artifacts/taobao-diagnostic.txt
    if ! grep -q '^OK (1 test)' artifacts/taobao-diagnostic.txt; then
        echo "Taobao diagnostic did not pass"
        test_status=1
    fi
fi
if [ "$test_status" -ne 0 ]; then
    adb logcat -d -v threadtime > artifacts/android-test-logcat.txt
fi
exit "$test_status"
