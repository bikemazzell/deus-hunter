package io.github.deushunt;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;

public class RecordInteractionTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();

  @Test
  public void gridFindOpensAssociatedRawEvidence() throws Exception {
    String find;
    try (HuntStore store = new HuntStore(context)) {
      String session = store.startSession("AA:BB:CC:DD:EE:FF");
      store.record(
          session,
          ForegroundFlowTest.observation(42, 100),
          new Fix(51, 0, 2, 99, null, null),
          true);
      store.stopSession(session);
      try (Cursor c =
          store
              .getReadableDatabase()
              .rawQuery(
                  "SELECT find_id FROM observations WHERE session_id=?", new String[] {session})) {
        assertTrue(c.moveToFirst());
        find = c.getString(0);
      }
    }
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      Field field = MainActivity.class.getDeclaredField("maps");
      field.setAccessible(true);
      MapController maps = (MapController) field.get(activity);
      Field gridField = MapController.class.getDeclaredField("grid");
      gridField.setAccessible(true);
      View grid = (View) gridField.get(maps);
      ForegroundFlowTest.await(() -> grid.getWidth() > 0 && grid.getHeight() > 0);
      instrumentation.runOnMainSync(
          () -> {
            maps.center(51, 0);
            maps.showPoints(
                Collections.singletonList(
                    new MapController.Point(find, 51, 0, "ID 48", "Stored evidence")));
            grid.setVisibility(View.VISIBLE);
            long now = SystemClock.uptimeMillis();
            grid.dispatchTouchEvent(
                MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_DOWN,
                    grid.getWidth() / 2f,
                    grid.getHeight() / 2f,
                    0));
            grid.dispatchTouchEvent(
                MotionEvent.obtain(
                    now,
                    now + 1,
                    MotionEvent.ACTION_UP,
                    grid.getWidth() / 2f,
                    grid.getHeight() / 2f,
                    0));
          });
      click("Records");
      click("ID 48");
      awaitText("raw_hex:");
      assertTrue(hasText("target_id: 48"));
      assertTrue(hasText("fix_age_ns: 1"));
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  @Test
  public void failedNoteMutationShowsErrorAndKeepsEvidence() throws Exception {
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try (HuntStore store = new HuntStore(context)) {
      String session = store.startSession("AA:BB:CC:DD:EE:FF");
      store.record(
          session, ForegroundFlowTest.observation(1, 100), new Fix(51, 0, 2, 99, null, null), true);
      store.stopSession(session);
      String find;
      try (Cursor c =
          store
              .getReadableDatabase()
              .rawQuery(
                  "SELECT find_id FROM observations WHERE session_id=?", new String[] {session})) {
        assertTrue(c.moveToFirst());
        find = c.getString(0);
      }
      store
          .getWritableDatabase()
          .execSQL(
              "CREATE TEMP TRIGGER fail_note BEFORE UPDATE OF note ON finds BEGIN SELECT"
                  + " RAISE(ABORT,'test disk full'); END");
      boolean[] escaped = {false};
      instrumentation.runOnMainSync(
          () -> {
            try {
              new RecordBrowser(activity, store, () -> {}, (a, b) -> {})
                  .mutate(() -> store.note(find, "should fail"));
            } catch (SQLiteException error) {
              escaped[0] = true;
            }
          });
      assertFalse("Storage failure must remain inside the UI boundary", escaped[0]);
      awaitText("Could not save this change");
      try (Cursor c =
          store
              .getReadableDatabase()
              .rawQuery("SELECT note FROM finds WHERE id=?", new String[] {find})) {
        assertTrue(c.moveToFirst());
        assertEquals("", c.getString(0));
      }
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  private boolean hasText(String text) {
    AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
    return root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty();
  }

  private void awaitText(String text) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 5000;
    while (!hasText(text) && SystemClock.uptimeMillis() < deadline) Thread.sleep(50);
    if (hasText(text)) return;
    StringBuilder tree = new StringBuilder();
    describe(instrumentation.getUiAutomation().getRootInActiveWindow(), tree);
    android.os.PowerManager power =
        (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
    fail(
        "Missing UI text: "
            + text
            + "; interactive="
            + power.isInteractive()
            + "; active window="
            + tree);
  }

  private void describe(AccessibilityNodeInfo node, StringBuilder out) {
    if (node == null) {
      out.append("null");
      return;
    }
    out.append('[')
        .append(node.getPackageName())
        .append(':')
        .append(node.getClassName())
        .append(':')
        .append(node.getText())
        .append(']');
    for (int i = 0; i < node.getChildCount(); i++) describe(node.getChild(i), out);
  }

  private void click(String text) throws Exception {
    awaitText(text);
    AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
      if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
      if (node.getParent() != null
          && node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
    }
    fail("Could not click " + text);
  }
}
