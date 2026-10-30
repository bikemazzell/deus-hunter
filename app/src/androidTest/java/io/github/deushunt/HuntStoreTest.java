package io.github.deushunt;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.deusble.core.Observation;
import java.io.*;
import org.junit.*;

public class HuntStoreTest {
  Context context;
  HuntStore store;

  @Before
  public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.deleteDatabase("test-hunts.db");
    store = new HuntStore(context, "test-hunts.db");
  }

  @After
  public void close() {
    store.close();
  }

  Observation obs(int frame, long mono) {
    byte[] raw = new byte[24];
    raw[0] = 2;
    raw[1] = 1;
    raw[2] = 5;
    raw[17] = 50;
    raw[19] = 4;
    raw[20] = (byte) frame;
    return new Observation("AA:BB:CC:DD:EE:FF", "Detector,\"one\"\n", -63, 1000 + frame, mono, raw);
  }

  @Test
  public void continuousRepeatsDoNotBecomeNewDetections() {
    String session = store.startSession("AA:BB:CC:DD:EE:FF");
    store.record(session, obs(7, 1_000_000_000L), null, true);
    store.record(session, obs(7, 4_000_000_000L), null, true);
    store.record(session, obs(7, 7_000_000_000L), null, true);
    assertEquals(1, store.count("observations"));
    assertEquals(3, store.count("receipts"));
  }

  @Test
  public void unlocatedRecoveryAndStoppedSession() throws Exception {
    String s = store.startSession("AA:BB:CC:DD:EE:FF");
    store.record(s, obs(1, 100), null, true);
    store.close();
    store = new HuntStore(context, "test-hunts.db");
    assertEquals(1, store.count("observations"));
    assertEquals(0, store.count("finds"));
    try (Cursor c = store.rows("observations")) {
      assertTrue(c.moveToFirst());
      assertTrue(c.isNull(c.getColumnIndexOrThrow("latitude")));
    }
    store.stopSession(s);
    try {
      store.record(s, obs(2, 101), null, true);
      fail();
    } catch (IllegalStateException expected) {
    }
    assertEquals(1, store.count("observations"));
  }

  @Test
  public void groupsRepeatsSplitsAndKeepsRaw() throws Exception {
    String s = store.startSession("AA:BB:CC:DD:EE:FF");
    Fix f = new Fix(51, 0, 3, 100, 4.0, 2.0);
    store.record(s, obs(127, 101), f, true);
    store.record(s, obs(127, 102), f, true);
    store.record(s, obs(128, 103), f, true);
    assertEquals(2, store.count("observations"));
    assertEquals(1, store.count("finds"));
    try (Cursor c = store.rows("observations")) {
      c.moveToFirst();
      assertEquals(2, c.getInt(c.getColumnIndexOrThrow("repeat_count")));
      assertEquals(48, c.getString(c.getColumnIndexOrThrow("raw_hex")).length());
      store.split(c.getString(c.getColumnIndexOrThrow("id")));
    }
    assertEquals(2, store.count("finds"));
    StringWriter json = new StringWriter();
    store.export(json, false);
    assertTrue(json.toString().contains("schema_version"));
    assertTrue(json.toString().contains("\\n"));
    StringWriter csv = new StringWriter();
    store.export(csv, true);
    assertTrue(csv.toString().contains("\"\"one\"\""));
  }

  @Test
  public void staleFutureAndSeparateSessions() {
    String s = store.startSession("AA:BB:CC:DD:EE:FF");
    store.record(s, obs(1, 100), new Fix(51, 0, 3, 101, null, null), true);
    store.record(s, obs(2, 20_000_000_000L), new Fix(51, 0, 3, 1, null, null), true);
    assertEquals(0, store.count("finds"));
    store.record(s, obs(3, 102), new Fix(51, 0, 3, 100, null, null), true);
    String other = store.startSession("AA:BB:CC:DD:EE:FF");
    store.record(other, obs(4, 102), new Fix(51, 0, 3, 100, null, null), true);
    assertEquals(2, store.count("finds"));
  }

  @Test
  public void transactionFailureRollsBackFindAndReceipt() {
    String session = store.startSession("AA:BB:CC:DD:EE:FF");
    store
        .getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER fail_insert BEFORE INSERT ON observations BEGIN SELECT"
                + " RAISE(ABORT,'test disk error'); END");
    try {
      store.record(session, obs(1, 100), new Fix(51, 0, 2, 99, null, null), true);
      fail();
    } catch (android.database.sqlite.SQLiteException expected) {
    }
    assertEquals(0, store.count("finds"));
    assertEquals(0, store.count("receipts"));
    assertEquals(0, store.count("observations"));
  }

  @Test
  public void recoveryRequiresExplicitResume() {
    String session = store.startSession("AA:BB:CC:DD:EE:FF");
    store.recover();
    try {
      store.record(session, obs(1, 100), null, true);
      fail();
    } catch (IllegalStateException expected) {
    }
    store.resume(session);
    store.record(session, obs(1, 100), null, true);
    assertEquals(1, store.count("observations"));
  }

  @Test
  public void jsonRoundTripAndLargeStreamingExport() throws Exception {
    String session = store.startSession("AA:BB:CC:DD:EE:FF");
    for (int i = 0; i < 300; i++) store.record(session, obs(i % 256, 100 + i), null, true);
    StringWriter output = new StringWriter();
    store.export(output, false);
    int observations = 0, receipts = 0;
    for (String line : output.toString().split("\n")) {
      org.json.JSONObject item = new org.json.JSONObject(line);
      assertEquals(1, item.getInt("schema_version"));
      if (item.getString("record_type").equals("observations")) {
        observations++;
        assertTrue(item.isNull("latitude"));
        assertEquals(50, item.getInt("target_id"));
        assertEquals(89, item.getInt("soil"));
        assertEquals("Detector,\"one\"\n", item.getString("name"));
      }
      if (item.getString("record_type").equals("receipts")) receipts++;
    }
    assertEquals(300, observations);
    assertEquals(300, receipts);
  }

  @Test
  public void exportsPreserveEveryScalarAndCsvEscaping() throws Exception {
    String session = store.startSession("AA:BB:CC:DD:EE:FF");
    store.record(session, obs(128, 102), new Fix(51.1, -0.1, 2.5, 100, 37.5, 1.25), true);
    StringWriter json = new StringWriter();
    store.export(json, false);
    java.util.Map<String, org.json.JSONObject> records = new java.util.HashMap<>();
    for (String line : json.toString().split("\n")) {
      org.json.JSONObject item = new org.json.JSONObject(line);
      records.put(item.getString("id"), item);
    }
    for (String table : new String[] {"sessions", "finds", "observations", "receipts"})
      try (Cursor c = store.rows(table)) {
        while (c.moveToNext()) {
          org.json.JSONObject item = records.get(c.getString(c.getColumnIndexOrThrow("id")));
          assertNotNull(item);
          assertEquals(table, item.getString("record_type"));
          for (int column = 0; column < c.getColumnCount(); column++) {
            String name = c.getColumnName(column);
            if (c.isNull(column)) assertTrue(item.isNull(name));
            else if (c.getType(column) == Cursor.FIELD_TYPE_INTEGER)
              assertEquals(c.getLong(column), item.getLong(name));
            else if (c.getType(column) == Cursor.FIELD_TYPE_FLOAT)
              assertEquals(c.getDouble(column), item.getDouble(name), 0);
            else assertEquals(c.getString(column), item.getString(name));
          }
        }
      }
    StringWriter csv = new StringWriter();
    store.export(csv, true);
    java.util.List<java.util.List<String>> rows = parseCsv(csv.toString());
    assertEquals(2, rows.size());
    assertEquals(rows.get(0).size(), rows.get(1).size());
    assertEquals("Detector,\"one\"\n", rows.get(1).get(rows.get(0).indexOf("name")));
    assertEquals(session, rows.get(1).get(rows.get(0).indexOf("session_id")));
    assertEquals("AUTO", rows.get(1).get(rows.get(0).indexOf("mode")));
    assertEquals("1.25", rows.get(1).get(rows.get(0).indexOf("speed")));
  }

  private static java.util.List<java.util.List<String>> parseCsv(String text) {
    java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
    java.util.List<String> row = new java.util.ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else quoted = !quoted;
      } else if (!quoted && c == ',') {
        row.add(field.toString());
        field.setLength(0);
      } else if (!quoted && c == '\n') {
        row.add(field.toString());
        field.setLength(0);
        rows.add(row);
        row = new java.util.ArrayList<>();
      } else if (quoted || c != '\r') field.append(c);
    }
    assertFalse(quoted);
    return rows;
  }

  @Test
  public void failedOutputKeepsData() throws Exception {
    String s = store.startSession("AA:BB:CC:DD:EE:FF");
    store.record(s, obs(1, 100), null, true);
    try {
      store.export(
          new Writer() {
            public void write(char[] c, int o, int l) throws IOException {
              throw new IOException("disk full");
            }

            public void flush() {}

            public void close() {}
          },
          false);
      fail();
    } catch (IOException expected) {
    }
    assertEquals(1, store.count("observations"));
  }
}
