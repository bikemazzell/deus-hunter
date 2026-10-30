package io.github.deushunt;

import android.app.*;
import android.content.SharedPreferences;
import android.widget.EditText;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.offline.*;

final class MapDownloads {
  private static final String DEFAULT = "https://tiles.openfreemap.org/styles/liberty";
  private final Activity a;
  private final SharedPreferences prefs;
  private final OfflineManager offline;
  private final MapNetwork network;
  private final Consumer<String> message;
  private final Consumer<OfflineRegion> showRegion;
  private OfflineRegion active;
  private boolean started;
  private int generation;

  MapDownloads(
      Activity a,
      SharedPreferences prefs,
      OfflineManager offline,
      MapNetwork network,
      Consumer<String> message,
      Consumer<OfflineRegion> showRegion) {
    this.a = a;
    this.prefs = prefs;
    this.offline = offline;
    this.network = network;
    this.message = message;
    this.showRegion = showRegion;
  }

  void onStart() {
    started = true;
  }

  void onStop() {
    started = false;
    generation++;
    pause();
  }

  private void say(String s) {
    if (!a.isDestroyed()) message.accept(s);
  }

  private void input(String title, String initial, java.util.function.Consumer<String> action) {
    EditText e = new EditText(a);
    e.setText(initial);
    new AlertDialog.Builder(a)
        .setTitle(title)
        .setView(e)
        .setPositiveButton(
            "OK",
            (d, w) -> {
              try {
                action.accept(e.getText().toString().trim());
              } catch (IllegalArgumentException ex) {
                say(ex.getMessage());
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  void downloadForm(double lat, double lon) {
    final int requestedGeneration = generation;
    input(
        "south, west, north, east, min zoom, max zoom",
        String.format(
            Locale.US,
            "%.5f, %.5f, %.5f, %.5f, 12, 15",
            lat - .005,
            lon - .005,
            lat + .005,
            lon + .005),
        s -> {
          String[] v = s.split(",");
          if (v.length != 6)
            throw new IllegalArgumentException("Enter four bounds and two zoom levels.");
          double south = Double.parseDouble(v[0].trim()),
              west = Double.parseDouble(v[1].trim()),
              north = Double.parseDouble(v[2].trim()),
              east = Double.parseDouble(v[3].trim());
          int min = Integer.parseInt(v[4].trim()), max = Integer.parseInt(v[5].trim());
          long tiles = MapPolicy.tiles(south, west, north, east, min, max);
          if (tiles > 2500)
            throw new IllegalArgumentException(
                "Region too large: at least "
                    + tiles
                    + " tiles. Choose smaller bounds or fewer zooms.");
          if (a.getFilesDir().getUsableSpace() < MapPolicy.MIN_FREE + MapPolicy.MAX_BYTES)
            throw new IllegalArgumentException("Region needs at least 150 MB free storage.");
          OfflineTilePyramidRegionDefinition def =
              new OfflineTilePyramidRegionDefinition(
                  prefs.getString("style", DEFAULT),
                  LatLngBounds.from(north, east, south, west),
                  min,
                  max,
                  a.getResources().getDisplayMetrics().density,
                  false);
          offline.createOfflineRegion(
              def,
              ("Region " + new Date()).getBytes(StandardCharsets.UTF_8),
              new OfflineManager.CreateOfflineRegionCallback() {
                public void onCreate(OfflineRegion r) {
                  if (started && requestedGeneration == generation) start(r);
                  else r.setDownloadState(OfflineRegion.STATE_INACTIVE);
                }

                public void onError(String e) {
                  say("Cannot create region: " + e);
                }
              });
        });
  }

  private synchronized void start(OfflineRegion r) {
    if (!started || a.isDestroyed()) {
      r.setDownloadState(OfflineRegion.STATE_INACTIVE);
      return;
    }
    pause();
    active = r;
    network.begin(r.getId());
    r.setObserver(
        new OfflineRegion.OfflineRegionObserver() {
          public void onStatusChanged(OfflineRegionStatus s) {
            if (active != r) return;
            say(
                "Region "
                    + r.getId()
                    + ": "
                    + s.getCompletedResourceCount()
                    + " / "
                    + s.getRequiredResourceCount()
                    + " resources, "
                    + s.getCompletedResourceSize() / 1024
                    + " KB · Maps → Saved regions to pause");
            if (s.isComplete()) {
              pause();
              say("Region " + r.getId() + " saved for offline use.");
            } else if (s.getCompletedTileCount() >= 3000
                || s.getCompletedResourceSize() >= MapPolicy.MAX_BYTES) {
              pause();
              say("Region stopped at provider limit. Choose a smaller region.");
            }
          }

          public void onError(OfflineRegionError e) {
            if (active != r) return;
            pause();
            say("Download paused: " + e.getMessage() + ". Resume from Saved regions.");
          }

          public void mapboxTileCountLimitExceeded(long n) {
            if (active != r) return;
            pause();
            say("Tile quota reached.");
          }
        });
    r.setDownloadState(OfflineRegion.STATE_ACTIVE);
  }

  synchronized void pause() {
    OfflineRegion r = active;
    if (r != null) r.setDownloadState(OfflineRegion.STATE_INACTIVE);
    active = null;
    network.end();
  }

  void regions() {
    final int requestedGeneration = generation;
    offline.listOfflineRegions(
        new OfflineManager.ListOfflineRegionsCallback() {
          public void onList(OfflineRegion[] all) {
            if (!started || requestedGeneration != generation || a.isDestroyed()) return;
            if (all.length == 0) {
              say("No saved regions.");
              return;
            }
            String[] labels = new String[all.length];
            for (int i = 0; i < all.length; i++)
              labels[i] =
                  "Region "
                      + all[i].getId()
                      + " · "
                      + new String(all[i].getMetadata(), StandardCharsets.UTF_8);
            new AlertDialog.Builder(a)
                .setTitle("Saved regions")
                .setItems(
                    labels,
                    (d, w) -> {
                      OfflineRegion r = all[w];
                      new AlertDialog.Builder(a)
                          .setTitle(labels[w])
                          .setItems(
                              new String[] {
                                "View", "Resume download", "Pause download", "Delete this region"
                              },
                              (dialog, index) -> {
                                if (index == 0) {
                                  showRegion.accept(r);
                                } else if (index == 1) start(r);
                                else if (index == 2) {
                                  pause();
                                  r.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                  say("Region paused. Saved resources remain.");
                                } else
                                  new AlertDialog.Builder(a)
                                      .setMessage("Delete this saved region? Hunt records remain.")
                                      .setNegativeButton("Keep", null)
                                      .setPositiveButton(
                                          "Delete",
                                          (x, y) -> {
                                            pause();
                                            r.delete(
                                                new OfflineRegion.OfflineRegionDeleteCallback() {
                                                  public void onDelete() {
                                                    say("Region deleted.");
                                                  }

                                                  public void onError(String e) {
                                                    say("Cannot delete region: " + e);
                                                  }
                                                });
                                          })
                                      .show();
                              })
                          .show();
                    })
                .show();
          }

          public void onError(String e) {
            say("Cannot list regions: " + e);
          }
        });
  }
}
