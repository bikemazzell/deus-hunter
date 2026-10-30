package io.github.deushunt;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.location.Location;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.deusble.core.Observation;
import java.io.*;
import java.util.function.BooleanSupplier;
import org.junit.Test;

public class ForegroundFlowTest {
  @Test
  public void backgroundServiceRecordsStopsExportsAndReopens() throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = instrumentation.getTargetContext();
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant io.github.deushunt android.permission.BLUETOOTH_SCAN")
        .close();
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant io.github.deushunt android.permission.BLUETOOTH_CONNECT")
        .close();
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant io.github.deushunt android.permission.ACCESS_FINE_LOCATION")
        .close();
    instrumentation
        .getUiAutomation()
        .executeShellCommand("pm grant io.github.deushunt android.permission.POST_NOTIFICATIONS")
        .close();
    context
        .getSharedPreferences("maps", 0)
        .edit()
        .putString("style", "https://invalid.invalid/no-map.json")
        .commit();
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      instrumentation.runOnMainSync(
          () ->
              context.startForegroundService(
                  new Intent(context, DebugHuntService.class)
                      .putExtra("detector", "AA:BB:CC:DD:EE:FF")));
      await(() -> DebugHuntService.backend.callback != null);
      String session = HuntService.activeSession;
      assertNotNull(session);
      instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
      long time = 100; // Deliberately earlier than any real emulator GPS timestamp.
      DebugHuntService.backend.emit(observation(127, time));
      await(() -> count(context, session) == 1);
      Location fix = new Location("test");
      fix.setLatitude(51);
      fix.setLongitude(0);
      fix.setAccuracy(3);
      fix.setElapsedRealtimeNanos(time + 1);
      DebugHuntService.instance.onLocationChanged(fix);
      DebugHuntService.backend.emit(observation(128, time + 2));
      await(() -> count(context, session) == 2);
      NotificationManager notifications =
          (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      await(() -> notifications.getActiveNotifications().length > 0);
      notifications.getActiveNotifications()[0].getNotification().actions[0].actionIntent.send();
      await(() -> HuntService.activeSession == null);
      assertNull(DebugHuntService.backend.callback);
      try (HuntStore store = new HuntStore(context)) {
        StringWriter out = new StringWriter();
        store.export(out, false);
        assertTrue(out.toString().contains(session));
        assertTrue(out.toString().contains("\"latitude\":51"));
        assertTrue(out.toString().contains("\"latitude\":null"));
      }
      java.io.File destination = new java.io.File(context.getCacheDir(), "foreground-export.jsonl");
      if (destination.exists()) assertTrue(destination.delete());
      instrumentation.runOnMainSync(
          () ->
              ((MainActivity) activity)
                  .onActivityResult(
                      20,
                      Activity.RESULT_OK,
                      new Intent().setData(android.net.Uri.fromFile(destination))));
      await(() -> exportHasSession(destination, session));
      String fileText =
          new String(
              java.nio.file.Files.readAllBytes(destination.toPath()),
              java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(fileText.contains(session));
      assertTrue(fileText.contains("\"record_type\":\"receipts\""));
      instrumentation.runOnMainSync(activity::finish);
      Activity reopened =
          instrumentation.startActivitySync(
              new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        assertEquals(2, count(context, session));
        assertNull(HuntService.activeSession);
      } finally {
        instrumentation.runOnMainSync(reopened::finish);
      }
    } finally {
      context.stopService(new Intent(context, DebugHuntService.class));
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  private static boolean exportHasSession(File file, String session) {
    if (!file.isFile()) return false;
    try {
      String content =
          new String(
              java.nio.file.Files.readAllBytes(file.toPath()),
              java.nio.charset.StandardCharsets.UTF_8);
      return content.endsWith("\n") && content.split(session, -1).length >= 6;
    } catch (IOException e) {
      return false;
    }
  }

  static Observation observation(int frame, long time) {
    byte[] b = new byte[24];
    b[0] = 2;
    b[1] = 1;
    b[2] = 5;
    b[17] = 48;
    b[19] = 4;
    b[20] = (byte) frame;
    return new Observation(
        "AA:BB:CC:DD:EE:FF", "Fixture", -60, System.currentTimeMillis(), time, b);
  }

  static int count(Context context, String session) {
    try (HuntStore store = new HuntStore(context);
        android.database.Cursor c =
            store
                .getReadableDatabase()
                .rawQuery(
                    "SELECT count(*) FROM observations WHERE session_id=?",
                    new String[] {session})) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  static void await(BooleanSupplier condition) throws Exception {
    long end = SystemClock.uptimeMillis() + 5000;
    while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) Thread.sleep(50);
    assertTrue("Timed out waiting for committed service state", condition.getAsBoolean());
  }
}
