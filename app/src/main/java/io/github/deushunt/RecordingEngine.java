package io.github.deushunt;

import io.github.deusble.android.DetectorScanner;
import io.github.deusble.android.ScanBackend;
import io.github.deusble.core.Observation;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Scanner and location boundary shared by the service and deterministic tests. */
final class RecordingEngine {
  private final DetectorScanner scanner;
  private final HuntStore store;
  private final String session;
  private final Consumer<String> status;
  private boolean running, grouping = true;
  private final java.util.ArrayDeque<Fix> fixes = new java.util.ArrayDeque<>();

  RecordingEngine(
      HuntStore store,
      String session,
      String address,
      ScanBackend backend,
      Consumer<String> status) {
    this(store, session, address, backend, status, Runnable::run);
  }

  RecordingEngine(
      HuntStore store,
      String session,
      String address,
      ScanBackend backend,
      Consumer<String> status,
      Executor executor) {
    this.store = store;
    this.session = session;
    this.status = status;
    scanner =
        new DetectorScanner(
            backend,
            new DetectorScanner.Listener() {
              @Override
              public void onObservation(Observation observation) {
                executor.execute(() -> record(observation));
              }

              @Override
              public void onError(DetectorScanner.Error error, int code) {
                status.accept(
                    "BLE collection unavailable: "
                        + error
                        + " ("
                        + code
                        + "). End and restart after fixing Bluetooth or permissions.");
              }
            });
    scanner.selectAddress(address);
  }

  synchronized void setGrouping(boolean enabled) {
    grouping = enabled;
  }

  synchronized void start() {
    running = true;
    status.accept("Collecting. No map or GPS fix is required.");
    scanner.start();
  }

  synchronized void stop() {
    running = false;
    scanner.stop();
  }

  synchronized void location(Fix fix) {
    if (!fix.valid()) return;
    fixes.addLast(fix);
    while (fixes.size() > 120) fixes.removeFirst();
  }

  private synchronized void record(Observation observation) {
    if (!running) return;
    try {
      Fix best = null;
      for (Fix candidate : fixes)
        if (GeoRules.recent(observation.monotonicNanos(), candidate.monotonicNanos)
            && (best == null || candidate.monotonicNanos > best.monotonicNanos)) best = candidate;
      store.record(session, observation, best, grouping);
    } catch (RuntimeException error) {
      running = false;
      scanner.stop();
      status.accept(
          "Recording stopped after storage error: "
              + error.getMessage()
              + ". Previously committed data remains saved.");
    }
  }
}
