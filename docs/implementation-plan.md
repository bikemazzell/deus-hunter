# Deus Hunt implementation plan

Build a separate Apache-2.0 Android repository named deus-hunter. Consume deus-ble as a versioned library. Use Java 17, native Android views, SQLite, and MapLibre Native. Keep collection independent of map rendering and network access.

## User flow

Open on a map with session controls and detector selection. Center on GPS when permitted. Allow place search and coordinate entry. Persist searched places for offline reuse. Start a session without a map or GPS. Show unlocated observations in a list, never at coordinate zero. Show a coordinate grid when map resources are absent. Later map downloads display existing located finds.

Use a foreground service for BLE and GPS during active sessions. Request permissions in context. Handle denial, Bluetooth disabled, interrupted service, and storage failure visibly. Stop collection when the user ends the session. Restore saved sessions after process death, but require an explicit action to resume collection.

## Data contract

Store sessions, immutable observations, locations, and find associations in SQLite transactions. Each observation retains full advertisement, parsed fields, RSSI, detector identity, wall and monotonic times, frame/mode, location age, accuracy, altitude, and speed when available. Unknown values remain null. Use monotonic time to match location fixes within ten seconds. Never attach a future fix to an earlier observation silently.

Store distinct detections once while retaining repeat counts and first/last receipt times. Group located detections into a find within the same session and detector when within three meters and within five target-ID units. Require GPS accuracy at most ten meters for grouping. Keep an anchor coordinate to prevent chaining across a field. Retain all constituent observations and offer splitting/merging or disabling grouping. GPS proximity does not prove that two signals represent one object.

## Maps

Use MapLibre with an OSM-derived source that permits offline download. Do not prefetch from tile.openstreetmap.org. Support imported offline resources and explicit region downloads with zoom bounds, progress, cancellation, quota, and disk-space checks. Retain viewed resources in persistent storage; explicit saved regions remain until user deletion. Persist style, sprites, and fonts as well as tiles. Display source attribution.

Sources: https://operations.osmfoundation.org/policies/tiles/ and https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/index.html

## Export

Export one JSONL file through Android document creation. Include schema version, session records, finds, and every observation with stable IDs. Export CSV as one observation per row with session/find IDs and all scalar fields. Quote commas, quotes, and newlines correctly. Preserve raw hex and missing values. Stream exports rather than loading whole sessions into memory. Report failure without deleting source data.

## Test-first tasks

1. Create domain tests for location matching, proximity boundaries, missing GPS, poor accuracy, multiple detectors, frame wraparound, and separate sessions. Observe failures before implementing.
2. Add database tests for transactions, reopen, interrupted writes, session recovery, and all-field round trips. Implement persistence.
3. Test JSONL/CSV parsing round trips, escaping, nulls, large exports, and output failures. Implement exporters.
4. Build map-first activity, session service, permission flow, detector selection, detail/list screens, and export chooser. Add instrumentation tests with injected scan and location sources.
5. Implement place search, coordinates, persistent cache, and region management. Test local map resources without external services. Test cancellation, restart, and insufficient space.
6. Run emulator acceptance: start without network or map, inject detections and GPS, revisit a spot, end session, restart, export, and load map later. Repeat permission-denial and Bluetooth-disabled paths.
7. Run unit tests, instrumentation, lint, release assembly, and dependency review. Publish separate GitHub repository after owner/authentication is resolved.

## Release gates

An emulator cannot establish reception from a physical Deus II. Record that test separately. Do not describe target ID as a metal classification, RSSI as target depth, or unknown bytes as battery data. Do not copy proprietary app code or include research packages in either public repository.

## Storage and service details

Use session UUIDs and observation UUIDs. The sessions table stores start/end wall times and state. The observations table stores packet metadata and nullable GPS fields. The finds table stores an anchor coordinate, detector address, initial target ID, and session ID. Each observation optionally references one find. Enable foreign keys. Insert a new find and its first observation in one transaction.

Serialize service writes on one worker thread. The activity observes committed state and never owns the scan. A detector selection remains fixed during a session. Expose a discovery screen before session start. Release location listeners and scanner callbacks when stopping. Treat asynchronous scan failure as visible degraded collection, not successful recording.

Use a connectedDevice foreground service when Bluetooth permission exists. Add location service type only when location permission exists. Start the service from the visible activity. Request notification permission on API 33+, and keep in-app status available if notification permission is denied.

The library exposes io.github.deusble.core.Packet methods targetId(), soil(), frame(), mode(), and observation(). Observation exposes address(), name(), rssi(), wallTimeMillis(), monotonicNanos(), and rawBytes(). The Android DetectorScanner listener exposes onDevice, onObservation, onDetection, onParseFailure, onState, and onError. Select a device with selectAddress before collecting detections.

Use io.github.deusble:deus-ble-android:0.1.0-SNAPSHOT and io.github.deusble:deus-ble-core:0.1.0-SNAPSHOT initially. Resolve the development Maven repository through a Gradle property, not an absolute path in public source.

## Scope choices

Record all distinct valid detections during a session. Do not silently suppress iron or require a repeated-target heuristic. Users can filter display later without losing evidence. Spatial grouping is reversible because observations remain immutable. Export JSONL is the canonical lossless format; CSV is a flat view.

Do not add cloud accounts, automatic backups, target-material predictions, or social features. A map provider is independent of the hunt database. Geocoding failures cannot stop collection. Coordinate entry works without a network.

## Default map source

OpenFreeMap permits bounded offline downloads under its October 14, 2026 terms: https://openfreemap.org/tos/. Use https://tiles.openfreemap.org/styles/liberty as the initial style. Limit downloads conservatively to both 3,000 tiles and 50 MB, use at most four parallel requests, and send an app-specific User-Agent. Allow the user to change the source. Do not issue region downloads during automated tests against the public service.

Use Android Geocoder for initial place search, with coordinate entry and saved search results as offline alternatives. Report unavailable geocoding clearly. Do not depend on the public Nominatim service or assume a per-device rate limit satisfies its application-wide policy.

## Publication authorization

The user selected GitHub owner bikemazzell. Create the public repository under that account. The client must work without paid services and support a configurable provider and imported maps.

## Imported map format

The first supported import format is a MapLibre offline-region SQLite database through OfflineManager.mergeOfflineRegions. Label this exact format in the file picker and help text. Do not label it as generic MBTiles support. Copy selected content into temporary private storage, reject unreadable or invalid databases visibly, merge through the SDK, and remove only the temporary copy. Keep existing maps and hunt records on failure.

Pin MapLibre Android 13.7.1, which is available from Maven Central. Its OfflineManager supports mergeOfflineRegions and persistent regions. Configure its HTTP client with an identifying User-Agent and a four-request concurrency ceiling. Test all network limits with local responses rather than public bulk downloads.
