package io.github.deushunt;

import android.app.Activity;
import android.content.Intent;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.maplibre.android.offline.*;

final class MapImporter {
  static final int IMPORT = 7412;
  private final Activity a;
  private final OfflineManager offline;
  private final java.util.function.Consumer<String> message;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile boolean destroyed;

  MapImporter(Activity a, OfflineManager offline, java.util.function.Consumer<String> message) {
    this.a = a;
    this.offline = offline;
    this.message = message;
  }

  void close() {
    destroyed = true;
    worker.shutdownNow();
  }

  void say(String text) {
    if (!destroyed) message.accept(text);
  }

  public boolean onActivityResult(int request, int result, Intent data) {
    if (request != IMPORT) return false;
    if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
    say("Importing MapLibre offline-region SQLite database…");
    worker.execute(
        () -> {
          File tmp = null;
          try {
            tmp = File.createTempFile("map-import-", ".db", a.getCacheDir());
            try (InputStream in = a.getContentResolver().openInputStream(data.getData());
                OutputStream out = new FileOutputStream(tmp)) {
              if (in == null) throw new IOException("Cannot read selected file.");
              byte[] buffer = new byte[8192];
              int n;
              while ((n = in.read(buffer)) != -1) {
                if (destroyed || Thread.currentThread().isInterrupted())
                  throw new IOException("Import canceled because the map screen closed.");
                if (a.getFilesDir().getUsableSpace() < MapPolicy.MIN_FREE + tmp.length())
                  throw new IOException("Insufficient disk space to copy and merge this database.");
                out.write(buffer, 0, n);
              }
            }
            File copy = tmp;
            a.runOnUiThread(
                () -> {
                  if (destroyed) {
                    copy.delete();
                    return;
                  }
                  offline.mergeOfflineRegions(
                      copy.getAbsolutePath(),
                      new OfflineManager.MergeOfflineRegionsCallback() {
                        public void onMerge(OfflineRegion[] regions) {
                          copy.delete();
                          say(
                              "Imported "
                                  + regions.length
                                  + " regions. Open Saved regions to view.");
                        }

                        public void onError(String e) {
                          copy.delete();
                          say("Invalid or unreadable MapLibre region database: " + e);
                        }
                      });
                });
          } catch (IOException | SecurityException e) {
            if (tmp != null) tmp.delete();
            say("Map import failed: " + e.getMessage());
          }
        });
    return true;
  }
}
