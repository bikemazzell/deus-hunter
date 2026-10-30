# Deus Hunter

A local Android field notebook for XP Deus II Bluetooth advertisements. The map, GPS, and network are optional for recording. The app uses the independent [deus-ble library](https://github.com/bikemazzell/deus-ble); it does not contain proprietary app code.

## Use

1. Grant Bluetooth and precise location permissions when prompted. The BLE library needs precise location permission even when you do not want to attach GPS coordinates. A GPS fix is optional. Notification permission controls notification visibility; in-app status remains available.
2. Tap **Detector**. Select the candidate that matches your detector, or enter its Bluetooth address. A matching advertisement is not proof of device identity.
3. Tap **Start**. The foreground service owns collection while you use another app. Check the status line for permission, Bluetooth, or storage errors.
4. Tap **Stop**, or use **Stop** in the foreground notification. **Records** shows saved evidence, including unlocated detections. **History** shows sessions and offers an explicit resume after interruption.
5. Use **More → Export JSONL** for a complete single-file archive. CSV provides one row per distinct valid detection. Android's file picker chooses the destination. Failed exports leave source records intact; discard a partial destination file before retrying.

**Maps** offers coordinates, place search, saved places, downloads, imports, and a coordinate grid. Place search uses Android Geocoder and may require a network. Successful searches and coordinates remain available offline. Map failures cannot stop the recording service. New map resources can display previously saved located finds. Tap a map or grid marker, then **Records**, to inspect every detection associated with that find.

Target ID is not a metal classification. RSSI is Bluetooth signal strength, not target depth. The app does not infer battery state or target material.

## Evidence and grouping

SQLite stores sessions, finds, detections, and individual advertisement receipts. Records retain the full advertisement as hex, detector address and name, RSSI, wall and monotonic times, target ID, soil value, frame, and PUSH/AUTO mode. A recent past GPS fix adds latitude, longitude, accuracy, fix time and age, altitude, and speed. Missing values remain null. Invalid, future, or older-than-ten-second fixes do not supply coordinates. The recorder retains a bounded history of fixes for delayed BLE callbacks.

Consecutive equal frames within a five-second gap count as repeats of the same detection. Every received advertisement still has a receipt with its original metadata. Frame changes and wraparound create distinct detections. Invalid packets retain their bytes and parse failure as receipts.

Grouping uses the same session and detector, target IDs within five units, GPS accuracy of at most ten meters, and distance of at most three meters from a fixed find anchor. A group does not prove that signals came from one object. Turn grouping off between sessions, or use **Records → detection → Split find**. Notes enrich a find without changing original evidence. JSONL retains every relationship and receipt; CSV is a flat view of detections.

A process restart never resumes collection silently. An interrupted session keeps all committed records. Resume or end it from History. The database writes each new find, detection, and receipt in one transaction. A failed write rolls back that transaction and stops recording visibly.

## Maps and costs

There is no paid service or account requirement. The default style is [OpenFreeMap Liberty](https://tiles.openfreemap.org/styles/liberty), under [OpenFreeMap's terms](https://openfreemap.org/tos/). Attribution appears on the map. The source URL is configurable; use a provider that permits the intended offline use. The app rejects the public OpenStreetMap tile server for downloads.

Viewed map resources use persistent storage. Explicit regions remain until deletion. Missing resources still require a network; browsing one viewport does not download every zoom level. Downloads have zoom and area bounds, progress, pause/resume, a 3,000-resource ceiling, a 50 MB ceiling, a four-request concurrency limit, and disk-space checks. The resource ceiling is stricter than a tile-only limit. Quotas persist across pause and restart. Each request uses an app-specific User-Agent.

Imports accept **MapLibre offline-region SQLite databases** through the SDK merge API. This is not generic MBTiles support. The app copies the selected file into temporary private storage, merges it, and removes that temporary copy. Import failures preserve existing maps and hunt records.

Clearing app data or uninstalling removes local records and maps. Export before doing either. The app has no cloud backup or automatic upload.

## Build

The project uses Java 17 source compatibility, Android SDK 36, minimum API 26, Gradle 8.12, AGP 8.9.1, and MapLibre Native 13.7.1. Runtime acceptance currently targets an API 36 emulator. The release APK excludes the debug service and test injection backend.

The BLE artifacts are not published on Maven Central. Build the pinned library commit locally beside this repository:

```sh
git clone https://github.com/bikemazzell/deus-ble.git ../deus-ble
git -C ../deus-ble checkout b79fd3d9f0780aa3e25f32ab44e46a5dcde0bf38
(cd ../deus-ble && ./gradlew publish)
./gradlew -PdeusBleMaven=../deus-ble/build/maven testDebugUnitTest lintDebug assembleRelease
./gradlew -PdeusBleMaven=../deus-ble/build/maven connectedDebugAndroidTest
```

Set `ANDROID_HOME` or an untracked `local.properties` file to your SDK path. Release assembly produces an unsigned APK; sign it with your own release key. CI builds the same pinned library before the client.

## Tests and limits

Unit tests cover coordinate age, grouping boundaries, and GPS validation. Instrumentation exercises database transactions, recovery, repeat receipts, streaming exports, fake BLE and GPS input, foreground collection while the activity is backgrounded, and restart behavior. Map tests use local resources; they do not bulk-download public map data. See [evidence](evidence/) for recorded runs and limits.

An emulator cannot prove reception from a physical Deus II. Before relying on this app in the field, compare target and soil values against a real detector. Test PUSH/AUTO, repeated broadcasts, new sweeps, range loss and return, screen-off reception, and GPS accuracy. Record firmware, phone, and Android versions separately from emulator evidence.

Apache-2.0. See [LICENSE](LICENSE). This project is independent of XP Metal Detectors.
