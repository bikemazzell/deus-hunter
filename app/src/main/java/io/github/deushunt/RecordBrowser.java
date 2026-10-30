package io.github.deushunt;

import android.app.*;
import android.database.Cursor;
import android.widget.EditText;
import java.util.*;
import java.util.function.BiConsumer;

/** Browses immutable evidence and edits only find associations and notes. */
final class RecordBrowser {
  private final Activity activity;
  private final HuntStore store;
  private final Runnable refresh;
  private final BiConsumer<String, String> resume;

  RecordBrowser(
      Activity activity, HuntStore store, Runnable refresh, BiConsumer<String, String> resume) {
    this.activity = activity;
    this.store = store;
    this.refresh = refresh;
    this.resume = resume;
  }

  private void message(String text) {
    new AlertDialog.Builder(activity).setMessage(text).setPositiveButton("Close", null).show();
  }

  void mutate(Runnable action) {
    try {
      action.run();
    } catch (android.database.sqlite.SQLiteException error) {
      message(
          "Could not save this change: "
              + error.getMessage()
              + ". Original evidence remains saved.");
    }
  }

  void records() {
    showRecords(null);
  }

  void showFind(String findId) {
    showRecords(findId);
  }

  private void showRecords(String findId) {
    ArrayList<String> labels = new ArrayList<>(), ids = new ArrayList<>();
    try (Cursor c =
        findId == null
            ? store.rows("observations")
            : store
                .getReadableDatabase()
                .query(
                    "observations",
                    null,
                    "find_id=?",
                    new String[] {findId},
                    null,
                    null,
                    "monotonic_ns")) {
      while (c.moveToNext()) {
        ids.add(value(c, "id"));
        labels.add(
            "ID "
                + value(c, "target_id")
                + " · soil "
                + value(c, "soil")
                + " · "
                + value(c, "mode")
                + "\n"
                + (c.isNull(c.getColumnIndexOrThrow("latitude"))
                    ? "Unlocated"
                    : value(c, "latitude") + ", " + value(c, "longitude"))
                + " · receipts "
                + value(c, "repeat_count"));
      }
    }
    if (ids.isEmpty()) {
      message("No detections yet. You can collect without map resources or a GPS fix.");
      return;
    }
    new AlertDialog.Builder(activity)
        .setTitle(findId == null ? "Saved detections" : "Detections at this find")
        .setItems(labels.toArray(new String[0]), (d, w) -> detail(ids.get(w)))
        .setNegativeButton("Close", null)
        .show();
  }

  private static String value(Cursor c, String name) {
    int i = c.getColumnIndexOrThrow(name);
    return c.isNull(i) ? "unknown" : c.getString(i);
  }

  private void detail(String id) {
    try (Cursor c =
        store
            .getReadableDatabase()
            .rawQuery("SELECT * FROM observations WHERE id=?", new String[] {id})) {
      if (!c.moveToFirst()) return;
      StringBuilder text = new StringBuilder();
      for (String column : c.getColumnNames())
        text.append(column).append(": ").append(value(c, column)).append('\n');
      String find = c.isNull(c.getColumnIndexOrThrow("find_id")) ? null : value(c, "find_id");
      AlertDialog.Builder b =
          new AlertDialog.Builder(activity)
              .setTitle("Detection evidence")
              .setMessage(text)
              .setPositiveButton("Close", null);
      if (find != null) {
        b.setNeutralButton(
            "Note",
            (d, w) -> {
              EditText input = new EditText(activity);
              new AlertDialog.Builder(activity)
                  .setTitle("Find note")
                  .setView(input)
                  .setPositiveButton(
                      "Save",
                      (x, y) -> {
                        mutate(
                            () -> {
                              store.note(find, input.getText().toString());
                              refresh.run();
                            });
                      })
                  .setNegativeButton("Cancel", null)
                  .show();
            });
        b.setNegativeButton(
            "Split find",
            (d, w) -> {
              mutate(
                  () -> {
                    store.split(id);
                    refresh.run();
                    message(
                        "This detection now has its own find. Its original evidence remains"
                            + " unchanged.");
                  });
            });
      }
      b.show();
    }
  }

  void history() {
    ArrayList<String> labels = new ArrayList<>(),
        ids = new ArrayList<>(),
        states = new ArrayList<>(),
        devices = new ArrayList<>();
    try (Cursor c = store.rows("sessions")) {
      while (c.moveToNext()) {
        ids.add(value(c, "id"));
        states.add(value(c, "state"));
        devices.add(value(c, "detector"));
        labels.add(
            new Date(c.getLong(c.getColumnIndexOrThrow("started_ms")))
                + "\n"
                + value(c, "state")
                + " · "
                + value(c, "detector"));
      }
    }
    if (ids.isEmpty()) {
      message("No sessions saved.");
      return;
    }
    new AlertDialog.Builder(activity)
        .setTitle("Session history")
        .setItems(
            labels.toArray(new String[0]),
            (d, w) -> {
              if (states.get(w).equals("interrupted"))
                new AlertDialog.Builder(activity)
                    .setTitle("Interrupted session")
                    .setMessage(
                        "Resume collection explicitly, or end this session. Committed records are"
                            + " safe.")
                    .setPositiveButton(
                        "Resume",
                        (x, y) -> {
                          resume.accept(devices.get(w), ids.get(w));
                        })
                    .setNegativeButton(
                        "End session", (x, y) -> mutate(() -> store.stopSession(ids.get(w))))
                    .setNeutralButton("Keep", null)
                    .show();
              else
                message(
                    "Session "
                        + ids.get(w)
                        + "\n"
                        + states.get(w)
                        + "\nUse Records to inspect observations; export includes all sessions.");
            })
        .setNegativeButton("Close", null)
        .show();
  }
}
