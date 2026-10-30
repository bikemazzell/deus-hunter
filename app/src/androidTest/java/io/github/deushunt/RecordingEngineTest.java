package io.github.deushunt;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;
import io.github.deusble.android.*;
import io.github.deusble.core.*;
import org.junit.*;

public class RecordingEngineTest {
  @Test
  public void injectedBleAndLocationRecordWithoutMapAndStopIgnoresLateCallbacks() {
    android.content.Context context =
        InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.deleteDatabase("engine-test.db");
    try (HuntStore store = new HuntStore(context, "engine-test.db")) {
      Fake backend = new Fake();
      String session = store.startSession("AA:BB:CC:DD:EE:FF");
      RecordingEngine engine =
          new RecordingEngine(store, session, "AA:BB:CC:DD:EE:FF", backend, s -> {});
      engine.start();
      byte[] bytes = new byte[24];
      bytes[0] = 2;
      bytes[1] = 1;
      bytes[2] = 5;
      bytes[17] = 42;
      bytes[20] = 127;
      backend.callback.onAdvertisement(
          new Observation("AA:BB:CC:DD:EE:FF", "D", -60, 1000, 100, bytes), true);
      engine.location(new Fix(51, 0, 2, 101, null, null));
      bytes[20] = (byte) 128;
      backend.callback.onAdvertisement(
          new Observation("AA:BB:CC:DD:EE:FF", "D", -60, 1001, 102, bytes), true);
      assertEquals(2, store.count("observations"));
      assertEquals(1, store.count("finds"));
      engine.stop();
      backend.callback.onAdvertisement(
          new Observation("AA:BB:CC:DD:EE:FF", "D", -60, 1002, 103, bytes), true);
      assertEquals(2, store.count("observations"));
    }
  }

  @Test
  public void delayedAdvertisementUsesLatestPastFix() {
    android.content.Context context =
        InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.deleteDatabase("delayed-test.db");
    try (HuntStore store = new HuntStore(context, "delayed-test.db")) {
      Fake backend = new Fake();
      String session = store.startSession("AA:BB:CC:DD:EE:FF");
      RecordingEngine engine =
          new RecordingEngine(store, session, "AA:BB:CC:DD:EE:FF", backend, s -> {});
      engine.start();
      engine.location(new Fix(51, 0, 2, 100, null, null));
      engine.location(new Fix(52, 0, 2, 200, null, null));
      backend.callback.onAdvertisement(ForegroundFlowTest.observation(1, 150), true);
      try (android.database.Cursor c = store.rows("observations")) {
        assertTrue(c.moveToFirst());
        assertEquals(51, c.getDouble(c.getColumnIndexOrThrow("latitude")), 0);
        assertEquals(50, c.getLong(c.getColumnIndexOrThrow("fix_age_ns")));
      }
      engine.stop();
    }
  }

  @Test
  public void unavailableBluetoothAndDeniedPermissionAreVisible() {
    android.content.Context context =
        InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.deleteDatabase("unavailable-test.db");
    try (HuntStore store = new HuntStore(context, "unavailable-test.db")) {
      for (ScanBackend.Availability reason :
          new ScanBackend.Availability[] {
            ScanBackend.Availability.BLUETOOTH_DISABLED, ScanBackend.Availability.MISSING_PERMISSION
          }) {
        final String[] message = {""};
        Fake backend = new Fake();
        backend.availability = reason;
        String session = store.startSession("AA:BB:CC:DD:EE:FF");
        RecordingEngine engine =
            new RecordingEngine(
                store, session, "AA:BB:CC:DD:EE:FF", backend, value -> message[0] = value);
        engine.start();
        assertTrue(message[0].contains(reason.name()));
        assertNull(backend.callback);
        assertEquals(0, store.count("observations"));
        engine.stop();
        store.stopSession(session);
      }
    }
  }

  static class Fake implements ScanBackend {
    Callback callback;
    Availability availability = Availability.READY;

    public Availability availability() {
      return availability;
    }

    public void start(Callback c) {
      callback = c;
    }

    public void stop(Callback c) {}
  }
}
