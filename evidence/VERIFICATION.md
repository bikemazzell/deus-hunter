# Verification — October 30, 2026

Final command:

```sh
./gradlew --max-workers=2 -PdeusBleMaven=../deus-ble/build/maven \
  testDebugUnitTest connectedDebugAndroidTest lintDebug assembleRelease
```

Result: **BUILD SUCCESSFUL in 57 seconds**. See [final-verification.txt](final-verification.txt).

- 10 unit tests passed; no failures, errors, or skips.
- 23 Android instrumentation tests passed on PlantLab-API36, Android 16; no failures, errors, or skips.
- Lint passed with zero errors and seven warnings. The warnings concern newer test dependencies, plural resources, legacy backup configuration, and conservative free-space checks.
- Release assembly produced `app/build/outputs/apk/release/app-release-unsigned.apk` (52,482,884 bytes).
- Inspection of every release DEX file found no `DebugHuntService`, `ForegroundFlowTest`, or `RecordInteractionTest` class name. Debug injection and instrumentation are excluded from release.
- The debug APK was installed and launched after the test runner removed its test installation. [app-launch.png](app-launch.png) shows the map-first layout and coordinate-grid fallback without any saved map or location permission.

The Android suite covers transactional rollback, database reopen, explicit interrupted-session recovery, every scalar JSONL field, parsed CSV quoting, output failure, repeat receipts, continuous repeated frames, frame wraparound, stale/future GPS rejection, delayed BLE timestamps, grouping and splitting, unavailable Bluetooth and permissions, foreground recording while the activity is backgrounded, the actual notification Stop action, activity destination-file export, history after recreation, rapid duplicate starts, same-turn UI Start/Stop, cleanup after a database error, map/grid-to-raw-record navigation, and failed note edits.

Map tests use local responses. They cover bounded requests and response bytes, persisted download quotas, fragmented response accounting, cached GeoJSON rendering with networking blocked, real MapLibre offline-region database import, and the stop/start download guard. No public bulk map download was used for automated tests.

Red-test logs retain the initial failures and later regression failures. Some intermediate build attempts also failed while parallel modules were incomplete. They are not final results.

The first combined attempt lost its emulator before reporting test results. Its log is [final-device-lost.txt](final-device-lost.txt). The emulator session exited with code 137; its log shows a graceful shutdown sequence. Host kernel logs were unavailable, so the exact cause remains unverified. The final successful run used a detached emulator, 2 GB guest RAM, two virtual cores, no snapshots, and two Gradle workers. No unrelated host processes were stopped.

Remaining evidence limits:

- Runtime testing used API 36. API 26 compatibility was checked by lint and build, not by a second emulator.
- A physical Deus II and Android phone were not available. Reception, display parity, range, and screen-off behavior still require hardware validation.
- The tests exercise the activity's destination-file result path. They do not automate every third-party Android document provider.
- Hosted GitHub CI status is separate from these local results and must be checked after publication.

The parent renamed the repository directory to `deus-hunter` and reran unit tests and release assembly. Both passed. See [relocation-build.txt](relocation-build.txt).
