package io.github.deushunt;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/** Streams a consistent database snapshot without loading the hunt into memory. */
final class HuntExporter {
  void export(HuntStore store, Writer out, boolean csv) throws IOException {
    SQLiteDatabase db = store.getReadableDatabase();
    db.beginTransaction();
    try {
      if (csv) {
        try (Cursor c = store.rows("observations")) {
          String[] columns = c.getColumnNames();
          writeCsv(out, columns);
          while (c.moveToNext()) {
            String[] values = new String[columns.length];
            for (int i = 0; i < values.length; i++) values[i] = c.isNull(i) ? "" : c.getString(i);
            writeCsv(out, values);
          }
        }
      } else
        for (String table : Arrays.asList("sessions", "finds", "observations", "receipts"))
          try (Cursor c = store.rows(table)) {
            while (c.moveToNext()) {
              JSONObject item = new JSONObject();
              try {
                item.put("schema_version", 1);
                item.put("record_type", table);
                for (int i = 0; i < c.getColumnCount(); i++) {
                  Object value =
                      c.isNull(i)
                          ? JSONObject.NULL
                          : c.getType(i) == Cursor.FIELD_TYPE_INTEGER
                              ? c.getLong(i)
                              : c.getType(i) == Cursor.FIELD_TYPE_FLOAT
                                  ? c.getDouble(i)
                                  : c.getString(i);
                  item.put(c.getColumnName(i), value);
                }
              } catch (JSONException impossible) {
                throw new IOException(impossible);
              }
              out.write(item.toString());
              out.write('\n');
            }
          }
      out.flush();
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private static void writeCsv(Writer out, String[] values) throws IOException {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) out.write(',');
      out.write('"');
      out.write(values[i].replace("\"", "\"\""));
      out.write('"');
    }
    out.write("\r\n");
  }
}
