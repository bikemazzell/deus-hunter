package io.github.deushunt;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Exercises the real service with the debug-only BLE backend and its real writer thread. */
public class HuntServiceLifecycleTest {
  @Test
  public void doubleStartQueuesOnlyOneSessionAndStopAllowsExplicitRestart() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    for (String permission :
        new String[] {"BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION"})
      i.getUiAutomation()
          .executeShellCommand("pm grant io.github.deushunt android.permission." + permission)
          .close();
    android.app.Activity activity =
        i.startActivitySync(
            new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ServiceConnection connection =
        new ServiceConnection() {
          public void onServiceConnected(ComponentName name, IBinder binder) {}

          public void onServiceDisconnected(ComponentName name) {}
        };
    Intent serviceIntent = new Intent(c, DebugHuntService.class);
    assertTrue(c.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE));
    CountDownLatch release = new CountDownLatch(1);
    try {
      ForegroundFlowTest.await(() -> DebugHuntService.instance != null);
      HuntService service = DebugHuntService.instance;
      Field field = HuntService.class.getDeclaredField("worker");
      field.setAccessible(true);
      Handler worker = (Handler) field.get(service);
      CountDownLatch blocked = new CountDownLatch(1);
      worker.post(
          () -> {
            blocked.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(blocked.await(3, TimeUnit.SECONDS));
      int before;
      try (HuntStore store = new HuntStore(c)) {
        before = store.count("sessions");
      }
      Intent start =
          new Intent(c, DebugHuntService.class).putExtra("detector", "AA:BB:CC:DD:EE:FF");
      i.runOnMainSync(
          () -> {
            service.onStartCommand(start, 0, 1);
            service.onStartCommand(start, 0, 2);
          });
      release.countDown();
      CountDownLatch drained = new CountDownLatch(1);
      worker.post(drained::countDown);
      assertTrue(drained.await(5, TimeUnit.SECONDS));
      try (HuntStore store = new HuntStore(c)) {
        assertEquals(before + 1, store.count("sessions"));
      }
      i.runOnMainSync(
          () -> {
            service.onStartCommand(new Intent().setAction(HuntService.STOP), 0, 3);
            service.onStartCommand(
                start, 0, 4); // A start while stopping must not acquire a scanner.
          });
      ForegroundFlowTest.await(() -> !HuntService.isCollecting());
      try (HuntStore store = new HuntStore(c)) {
        assertEquals(before + 1, store.count("sessions"));
      }
      assertNull(DebugHuntService.backend.callback);
      i.runOnMainSync(() -> service.onStartCommand(start, 0, 4));
      ForegroundFlowTest.await(() -> HuntService.activeSession != null);
      i.runOnMainSync(() -> service.onStartCommand(new Intent().setAction(HuntService.STOP), 0, 5));
      ForegroundFlowTest.await(() -> !HuntService.isCollecting());
    } finally {
      release.countDown();
      c.unbindService(connection);
      c.stopService(serviceIntent);
      i.runOnMainSync(activity::finish);
    }
  }

  @Test
  public void recoveryFailureStillReleasesScannerStateAndWriter() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    for (String permission :
        new String[] {"BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION"})
      i.getUiAutomation()
          .executeShellCommand("pm grant io.github.deushunt android.permission." + permission)
          .close();
    android.app.Activity activity =
        i.startActivitySync(
            new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    ServiceConnection connection =
        new ServiceConnection() {
          public void onServiceConnected(ComponentName name, IBinder binder) {}

          public void onServiceDisconnected(ComponentName name) {}
        };
    Intent serviceIntent = new Intent(c, DebugHuntService.class);
    assertTrue(c.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE));
    boolean bound = true;
    try {
      ForegroundFlowTest.await(() -> DebugHuntService.instance != null);
      HuntService service = DebugHuntService.instance;
      i.runOnMainSync(
          () ->
              service.onStartCommand(
                  serviceIntent.putExtra("detector", "AA:BB:CC:DD:EE:FF"), 0, 1));
      ForegroundFlowTest.await(() -> DebugHuntService.backend.callback != null);
      Field field = HuntService.class.getDeclaredField("thread");
      field.setAccessible(true);
      Thread writer = (Thread) field.get(service);
      try (HuntStore store = new HuntStore(c)) {
        store
            .getWritableDatabase()
            .execSQL(
                "CREATE TRIGGER fail_service_recovery BEFORE UPDATE OF state ON sessions WHEN"
                    + " NEW.state='interrupted' BEGIN SELECT RAISE(ABORT,'injected recovery"
                    + " failure'); END");
      }
      c.unbindService(connection);
      bound = false;
      ForegroundFlowTest.await(() -> !writer.isAlive());
      assertNull(DebugHuntService.backend.callback);
      assertNull(HuntService.activeSession);
      assertFalse(HuntService.isCollecting());
      assertTrue(HuntService.status.contains("injected recovery failure"));
    } finally {
      if (bound) c.unbindService(connection);
      c.stopService(serviceIntent);
      try (HuntStore store = new HuntStore(c)) {
        store.getWritableDatabase().execSQL("DROP TRIGGER IF EXISTS fail_service_recovery");
      }
      i.runOnMainSync(activity::finish);
    }
  }

  @Test
  public void activityStartAndStopInSameMainThreadTurnEndsSession() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    for (String permission :
        new String[] {
          "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS"
        })
      i.getUiAutomation()
          .executeShellCommand("pm grant io.github.deushunt android.permission." + permission)
          .close();
    c.getSharedPreferences("hunt", 0).edit().putString("detector", "AA:BB:CC:DD:EE:FF").commit();
    android.app.Activity activity =
        i.startActivitySync(
            new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    int before;
    try (HuntStore store = new HuntStore(c)) {
      before = store.count("sessions");
    }
    try {
      i.runOnMainSync(
          () -> {
            android.view.View root = activity.findViewById(android.R.id.content);
            android.widget.Button start = findButton(root, "Start"),
                stop = findButton(root, "Stop");
            assertNotNull(start);
            assertNotNull(stop);
            start.performClick();
            stop.performClick();
          });
      // Await committed completion, not an initially idle gate before intents arrive.
      ForegroundFlowTest.await(
          () -> {
            try (HuntStore store = new HuntStore(c);
                android.database.Cursor rows =
                    store
                        .getReadableDatabase()
                        .rawQuery("SELECT state FROM sessions ORDER BY rowid DESC LIMIT 1", null)) {
              return store.count("sessions") == before + 1
                  && rows.moveToFirst()
                  && "ended".equals(rows.getString(0))
                  && !HuntService.isCollecting();
            }
          });
      assertNull(HuntService.activeSession);
      android.app.NotificationManager notifications =
          (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
      assertEquals(0, notifications.getActiveNotifications().length);
    } finally {
      c.stopService(new Intent(c, HuntService.class));
      i.runOnMainSync(activity::finish);
    }
  }

  private static android.widget.Button findButton(android.view.View view, String text) {
    if (view instanceof android.widget.Button
        && text.contentEquals(((android.widget.Button) view).getText()))
      return (android.widget.Button) view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int n = 0; n < group.getChildCount(); n++) {
        android.widget.Button button = findButton(group.getChildAt(n), text);
        if (button != null) return button;
      }
    }
    return null;
  }
}
