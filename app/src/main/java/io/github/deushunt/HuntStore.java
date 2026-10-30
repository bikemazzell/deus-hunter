package io.github.deushunt;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import io.github.deusble.core.*;
import java.io.*;
import java.util.*;

/** Local evidence store. All writes are transactional; map resources are independent. */
final class HuntStore extends SQLiteOpenHelper {
  static final String DB = "hunts.db";

  HuntStore(Context context) {
    this(context, DB);
  }

  HuntStore(Context context, String name) {
    super(context, name, null, 1);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE sessions(id TEXT PRIMARY KEY, detector TEXT NOT NULL, started_ms INTEGER NOT"
            + " NULL, ended_ms INTEGER, state TEXT NOT NULL)");
    db.execSQL(
        "CREATE TABLE finds(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id),"
            + " detector TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy"
            + " REAL NOT NULL, target_id INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')");
    db.execSQL(
        "CREATE TABLE observations(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES"
            + " sessions(id), find_id TEXT REFERENCES finds(id), detector TEXT NOT NULL, name TEXT,"
            + " rssi INTEGER NOT NULL, wall_ms INTEGER NOT NULL, monotonic_ns INTEGER NOT NULL,"
            + " raw_hex TEXT NOT NULL, target_id INTEGER NOT NULL, soil INTEGER NOT NULL, frame"
            + " INTEGER NOT NULL, mode TEXT NOT NULL, latitude REAL, longitude REAL, accuracy REAL,"
            + " fix_monotonic_ns INTEGER, fix_age_ns INTEGER, altitude REAL, speed REAL,"
            + " repeat_count INTEGER NOT NULL, last_wall_ms INTEGER NOT NULL, last_monotonic_ns"
            + " INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE receipts(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES"
            + " sessions(id), observation_id TEXT REFERENCES observations(id), detector TEXT NOT"
            + " NULL, name TEXT, rssi INTEGER NOT NULL, wall_ms INTEGER NOT NULL, monotonic_ns"
            + " INTEGER NOT NULL, raw_hex TEXT, parse_failure TEXT)");
    db.execSQL("CREATE INDEX observations_session ON observations(session_id,monotonic_ns)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int old, int next) {
    throw new IllegalStateException("Unsupported schema upgrade");
  }

  String startSession(String detector) {
    ContentValues v = new ContentValues();
    String id = UUID.randomUUID().toString();
    v.put("id", id);
    v.put("detector", detector);
    v.put("started_ms", System.currentTimeMillis());
    v.put("state", "active");
    getWritableDatabase().insertOrThrow("sessions", null, v);
    return id;
  }

  void recover() {
    getWritableDatabase().execSQL("UPDATE sessions SET state='interrupted' WHERE state='active'");
  }

  void resume(String id) {
    ContentValues v = new ContentValues();
    v.put("state", "active");
    if (getWritableDatabase()
            .update("sessions", v, "id=? AND state='interrupted'", new String[] {id})
        != 1) throw new IllegalStateException("Session cannot resume");
  }

  void stopSession(String id) {
    ContentValues v = new ContentValues();
    v.put("state", "ended");
    v.put("ended_ms", System.currentTimeMillis());
    getWritableDatabase().update("sessions", v, "id=?", new String[] {id});
  }

  synchronized void record(String session, Observation observation, Fix fix, boolean grouping) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      try (Cursor s =
          db.rawQuery(
              "SELECT detector FROM sessions WHERE id=? AND state='active'",
              new String[] {session})) {
        if (!s.moveToFirst()) throw new IllegalStateException("Session is not active");
        if (!s.getString(0).equals(observation.address()))
          throw new IllegalArgumentException("Different detector");
      }
      ParseResult parsed = AdvertisementParser.parse(observation);
      String id = null;
      if (parsed.packet() != null) {
        Packet p = parsed.packet();
        // Consecutive frames only. Wraparound and later revisits remain distinct.
        try (Cursor previous =
            db.rawQuery(
                "SELECT id,frame,last_monotonic_ns FROM observations WHERE session_id=? ORDER BY"
                    + " rowid DESC LIMIT 1",
                new String[] {session})) {
          if (previous.moveToFirst()
              && previous.getInt(1) == p.frame()
              && observation.monotonicNanos() >= previous.getLong(2)
              && observation.monotonicNanos() - previous.getLong(2) <= 5_000_000_000L)
            id = previous.getString(0);
        }
        if (id != null) {
          db.execSQL(
              "UPDATE observations SET"
                  + " repeat_count=repeat_count+1,last_wall_ms=?,last_monotonic_ns=? WHERE id=?",
              new Object[] {observation.wallTimeMillis(), observation.monotonicNanos(), id});
        } else {
          id = UUID.randomUUID().toString();
          ContentValues v = metadata(observation);
          v.put("id", id);
          v.put("session_id", session);
          v.put("target_id", p.targetId());
          v.put("soil", p.soil());
          v.put("frame", p.frame());
          v.put("mode", p.mode().name());
          v.put("repeat_count", 1);
          v.put("last_wall_ms", observation.wallTimeMillis());
          v.put("last_monotonic_ns", observation.monotonicNanos());
          if (fix != null
              && fix.valid()
              && GeoRules.recent(observation.monotonicNanos(), fix.monotonicNanos)) {
            v.put("latitude", fix.latitude);
            v.put("longitude", fix.longitude);
            v.put("accuracy", fix.accuracy);
            v.put("fix_monotonic_ns", fix.monotonicNanos);
            v.put("fix_age_ns", observation.monotonicNanos() - fix.monotonicNanos);
            v.put("altitude", fix.altitude);
            v.put("speed", fix.speed);
            String find = null;
            if (grouping)
              try (Cursor c =
                  db.rawQuery(
                      "SELECT id,latitude,longitude,accuracy,target_id FROM finds WHERE"
                          + " session_id=? AND detector=?",
                      new String[] {session, observation.address()})) {
                while (c.moveToNext())
                  if (GeoRules.group(
                      fix.latitude,
                      fix.longitude,
                      fix.accuracy,
                      p.targetId(),
                      c.getDouble(1),
                      c.getDouble(2),
                      c.getDouble(3),
                      c.getInt(4))) {
                    find = c.getString(0);
                    break;
                  }
              }
            if (find == null)
              find =
                  addFind(
                      db,
                      session,
                      observation.address(),
                      fix.latitude,
                      fix.longitude,
                      fix.accuracy,
                      p.targetId());
            v.put("find_id", find);
          }
          db.insertOrThrow("observations", null, v);
        }
      }
      ContentValues receipt = metadata(observation);
      receipt.put("id", UUID.randomUUID().toString());
      receipt.put("session_id", session);
      receipt.put("observation_id", id);
      receipt.put("parse_failure", parsed.failure() == null ? null : parsed.failure().name());
      db.insertOrThrow("receipts", null, receipt);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private static ContentValues metadata(Observation o) {
    ContentValues v = new ContentValues();
    v.put("detector", o.address());
    v.put("name", o.name());
    v.put("rssi", o.rssi());
    v.put("wall_ms", o.wallTimeMillis());
    v.put("monotonic_ns", o.monotonicNanos());
    v.put("raw_hex", hex(o.rawBytes()));
    return v;
  }

  static String hex(byte[] bytes) {
    if (bytes == null) return null;
    StringBuilder b = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) b.append(String.format(Locale.ROOT, "%02x", value & 255));
    return b.toString();
  }

  private String addFind(
      SQLiteDatabase db,
      String s,
      String detector,
      double lat,
      double lon,
      double acc,
      int target) {
    String id = UUID.randomUUID().toString();
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("session_id", s);
    v.put("detector", detector);
    v.put("latitude", lat);
    v.put("longitude", lon);
    v.put("accuracy", acc);
    v.put("target_id", target);
    db.insertOrThrow("finds", null, v);
    return id;
  }

  void split(String observationId) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT session_id,detector,latitude,longitude,accuracy,target_id FROM observations"
                + " WHERE id=? AND latitude IS NOT NULL",
            new String[] {observationId})) {
      if (!c.moveToFirst()) throw new IllegalArgumentException("Observation has no coordinate");
      String id =
          addFind(
              db,
              c.getString(0),
              c.getString(1),
              c.getDouble(2),
              c.getDouble(3),
              c.getDouble(4),
              c.getInt(5));
      ContentValues v = new ContentValues();
      v.put("find_id", id);
      db.update("observations", v, "id=?", new String[] {observationId});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  void note(String find, String note) {
    ContentValues v = new ContentValues();
    v.put("note", note);
    getWritableDatabase().update("finds", v, "id=?", new String[] {find});
  }

  int count(String table) {
    validateTable(table);
    try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM " + table, null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  private void validateTable(String table) {
    if (!Arrays.asList("sessions", "finds", "observations", "receipts").contains(table))
      throw new IllegalArgumentException("Unknown table");
  }

  Cursor rows(String table) {
    validateTable(table);
    return getReadableDatabase().query(table, null, null, null, null, null, "rowid");
  }

  void export(Writer out, boolean csv) throws IOException {
    new HuntExporter().export(this, out, csv);
  }
}
