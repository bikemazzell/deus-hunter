package io.github.deushunt;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.location.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.*;
import org.maplibre.android.maps.*;
import org.maplibre.android.module.http.HttpRequestImpl;
import org.maplibre.android.offline.*;

/** Map storage never shares a database or worker with hunt recording. */
public final class MapController {
  public static final class Point {
    public final String id, title, detail;
    public final double latitude, longitude;

    public Point(String id, double latitude, double longitude, String title, String detail) {
      this.id = id;
      this.latitude = latitude;
      this.longitude = longitude;
      this.title = title;
      this.detail = detail;
    }
  }

  private static final int IMPORT = 7412;
  private static final String DEFAULT = "https://tiles.openfreemap.org/styles/liberty";
  private final Activity a;
  private final FrameLayout host;
  private final SharedPreferences prefs;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final List<Point> points = new ArrayList<>();
  private final Map<Long, Point> markerPoints = new HashMap<>();
  private java.util.function.Consumer<String> pointListener;

  public void setOnPointClick(java.util.function.Consumer<String> listener) {
    pointListener = listener;
  }

  private void showPoint(Point point) {
    AlertDialog.Builder dialog =
        new AlertDialog.Builder(a)
            .setTitle(point.title)
            .setMessage(point.detail)
            .setPositiveButton("Close", null);
    if (pointListener != null)
      dialog.setNeutralButton("Records", (d, w) -> pointListener.accept(point.id));
    dialog.show();
  }

  private MapView view;
  private MapLibreMap map;
  private OfflineManager offline;
  private Grid grid;
  private TextView status;
  private MapDownloads downloads;
  private MapImporter importer;
  private MapNetwork network;
  private java.util.function.Consumer<String> quotaListener;
  private volatile boolean destroyed;
  private double lat = 51.5, lon = 0;
  private boolean gridOnly;

  public MapController(Activity activity, FrameLayout container) {
    a = activity;
    host = container;
    prefs = a.getSharedPreferences("maps", 0);
  }

  public void onCreate(Bundle state) {
    lat = Double.longBitsToDouble(prefs.getLong("lat", Double.doubleToLongBits(lat)));
    lon = Double.longBitsToDouble(prefs.getLong("lon", 0));
    grid = new Grid();
    host.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    status = new TextView(a);
    status.setTextColor(Color.BLACK);
    status.setBackgroundColor(0xddffffff);
    status.setPadding(12, 4, 12, 4);
    FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    MapLibre.getInstance(a);
    offline = OfflineManager.getInstance(a);
    quotaListener =
        text -> {
          downloads.pause();
          gridOnly = true;
          grid.setVisibility(View.VISIBLE);
          grid.bringToFront();
          status.bringToFront();
          say(text);
        };
    network = new MapNetwork(a.getApplicationContext(), prefs, quotaListener);
    HttpRequestImpl.setOkHttpClient(network.client);
    downloads =
        new MapDownloads(
            a,
            prefs,
            offline,
            network,
            this::say,
            r -> {
              if (map == null || destroyed) {
                say("Map is initializing. Try again shortly.");
                return;
              }
              gridOnly = false;
              view.setVisibility(View.VISIBLE);
              map.setOfflineRegionDefinition(
                  r.getDefinition(),
                  style -> {
                    if (destroyed || gridOnly) return;
                    renderPoints();
                  });
            });
    importer = new MapImporter(a, offline, this::say);
    offline.setMaximumAmbientCacheSize(
        Long.MAX_VALUE,
        new OfflineManager.FileSourceCallback() {
          public void onSuccess() {}

          public void onError(String e) {
            say("Map cache: " + e);
          }
        });
    view = new MapView(a);
    host.addView(view, new FrameLayout.LayoutParams(-1, -1));
    host.addView(status, label);
    view.onCreate(state);
    grid.bringToFront();
    status.bringToFront();
    view.addOnDidFailLoadingMapListener(
        e -> {
          if (destroyed) return;
          gridOnly = true;
          grid.setVisibility(View.VISIBLE);
          view.setVisibility(View.INVISIBLE);
          say("Map unavailable. Coordinate grid remains available. " + e);
        });
    view.addOnDidFinishRenderingFrameListener(
        (fully, encoding, rendering) -> {
          if (!destroyed && !gridOnly && fully) grid.setVisibility(View.GONE);
        });
    view.getMapAsync(
        m -> {
          if (destroyed) return;
          map = m;
          map.setPrefetchesTiles(false);
          map.setPrefetchZoomDelta(0);
          map.setOnMarkerClickListener(
              marker -> {
                Point point = markerPoints.get(marker.getId());
                if (point != null) showPoint(point);
                return true;
              });
          map.addOnCameraIdleListener(
              () -> {
                LatLng p = map.getCameraPosition().target;
                if (p != null) {
                  lat = p.getLatitude();
                  lon = p.getLongitude();
                  saveCenter();
                  grid.invalidate();
                }
              });
          loadStyle();
          center(lat, lon);
        });
    say("© OpenStreetMap contributors · OpenFreeMap · Maps: search, offline regions, source");
  }

  private void loadStyle() {
    if (map == null) return;
    gridOnly = false;
    grid.setVisibility(View.VISIBLE);
    grid.bringToFront();
    status.bringToFront();
    view.setVisibility(View.VISIBLE);
    map.setStyle(
        prefs.getString("style", DEFAULT),
        s -> {
          if (destroyed || gridOnly) return;

          renderPoints();
          say(
              DEFAULT.equals(prefs.getString("style", DEFAULT))
                  ? "© OpenStreetMap contributors · OpenFreeMap · Source attribution: map ⓘ"
                  : "Map source attribution: map ⓘ");
        });
  }

  private void say(String message) {
    a.runOnUiThread(
        () -> {
          if (!destroyed && status != null) status.setText(message);
        });
  }

  private void saveCenter() {
    prefs
        .edit()
        .putLong("lat", Double.doubleToLongBits(lat))
        .putLong("lon", Double.doubleToLongBits(lon))
        .apply();
  }

  public void center(double latitude, double longitude) {
    if (!Double.isFinite(latitude + longitude)
        || Math.abs(latitude) > 90
        || Math.abs(longitude) > 180) return;
    lat = latitude;
    lon = longitude;
    saveCenter();
    if (map != null) map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 15));
    if (grid != null) grid.invalidate();
  }

  public void showPoints(List<Point> value) {
    points.clear();
    points.addAll(value);
    renderPoints();
    if (grid != null) grid.invalidate();
  }

  private void renderPoints() {
    if (map == null || map.getStyle() == null) return;
    map.removeAnnotations();
    markerPoints.clear();
    for (Point p : points) {
      org.maplibre.android.annotations.Marker marker =
          map.addMarker(
              new MarkerOptions()
                  .position(new LatLng(p.latitude, p.longitude))
                  .title(p.title)
                  .snippet(p.detail));
      markerPoints.put(marker.getId(), p);
    }
  }

  public void showSettings() {
    new AlertDialog.Builder(a)
        .setTitle("Maps")
        .setItems(
            new String[] {
              "Coordinates",
              "Search place",
              "Saved places",
              "Download region",
              "Saved regions / resume",
              "Import MapLibre offline-region SQLite database",
              "Map source",
              "Coordinate grid",
              "Reload map",
              "Storage policy"
            },
            (d, i) -> {
              switch (i) {
                case 0:
                  coordinates();
                  break;
                case 1:
                  search();
                  break;
                case 2:
                  saved();
                  break;
                case 3:
                  downloads.downloadForm(lat, lon);
                  break;
                case 4:
                  downloads.regions();
                  break;
                case 5:
                  Intent intent =
                      new Intent(Intent.ACTION_OPEN_DOCUMENT)
                          .setType("*/*")
                          .addCategory(Intent.CATEGORY_OPENABLE);
                  a.startActivityForResult(intent, IMPORT);
                  break;
                case 6:
                  source();
                  break;
                case 7:
                  gridOnly = true;
                  view.setVisibility(View.INVISIBLE);
                  grid.setVisibility(View.VISIBLE);
                  say("Coordinate grid · center " + lat + ", " + lon + " · tap a find for details");
                  break;
                case 8:
                  loadStyle();
                  break;
                case 9:
                  new AlertDialog.Builder(a)
                      .setTitle("Map storage")
                      .setMessage(
                          "Viewed tiles, styles, fonts and sprites use persistent storage with no"
                              + " size eviction. Saved regions stay until you delete them. Missing"
                              + " resources still need a network. New map requests stop below 100"
                              + " MB free. Clearing app data or uninstalling removes maps. A region"
                              + " download is limited to 3,000 HTTP resources and 50 MB, including"
                              + " retries and other map traffic during the download. Paused regions"
                              + " retain their quota. Import only a MapLibre offline-region SQLite"
                              + " database, not MBTiles. Maps never delete hunt records.")
                      .setPositiveButton("Close", null)
                      .show();
                  break;
              }
            })
        .show();
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

  private void coordinates() {
    input(
        "Latitude, longitude",
        lat + ", " + lon,
        s -> {
          String[] p = s.split(",");
          if (p.length != 2) throw new IllegalArgumentException("Enter latitude, longitude.");
          double x = Double.parseDouble(p[0].trim()), y = Double.parseDouble(p[1].trim());
          if (!Double.isFinite(x + y) || Math.abs(x) > 90 || Math.abs(y) > 180)
            throw new IllegalArgumentException("Coordinates out of range.");
          center(x, y);
          savePlace(s, x, y);
        });
  }

  private void search() {
    input(
        "Search place (network may be needed)",
        "",
        q -> {
          say("Searching…");
          worker.execute(
              () -> {
                try {
                  if (!Geocoder.isPresent())
                    throw new IOException(
                        "No geocoder on this device. Use coordinates or saved places.");
                  List<android.location.Address> result =
                      new Geocoder(a, Locale.getDefault()).getFromLocationName(q, 5);
                  a.runOnUiThread(
                      () -> {
                        if (destroyed) return;
                        if (result == null || result.isEmpty()) {
                          say("No places found. Use coordinates or saved places.");
                          return;
                        }
                        String[] labels = new String[result.size()];
                        for (int i = 0; i < labels.length; i++)
                          labels[i] = result.get(i).getAddressLine(0);
                        new AlertDialog.Builder(a)
                            .setTitle("Places")
                            .setItems(
                                labels,
                                (d, w) -> {
                                  android.location.Address p = result.get(w);
                                  center(p.getLatitude(), p.getLongitude());
                                  savePlace(labels[w], p.getLatitude(), p.getLongitude());
                                })
                            .show();
                      });
                } catch (IOException | IllegalArgumentException e) {
                  say("Place search unavailable: " + e.getMessage());
                }
              });
        });
  }

  private void savePlace(String name, double x, double y) {
    Set<String> saved = new HashSet<>(prefs.getStringSet("places", Collections.emptySet()));
    saved.add(x + "|" + y + "|" + name);
    prefs.edit().putStringSet("places", saved).apply();
  }

  private void saved() {
    String[] values = prefs.getStringSet("places", Collections.emptySet()).toArray(new String[0]);
    if (values.length == 0) {
      say("No saved places. Enter coordinates or search first.");
      return;
    }
    new AlertDialog.Builder(a)
        .setTitle("Saved places")
        .setItems(
            values,
            (d, w) -> {
              String[] p = values[w].split("\\|", 3);
              center(Double.parseDouble(p[0]), Double.parseDouble(p[1]));
            })
        .show();
  }

  private void source() {
    input(
        "HTTPS style URL (provider must permit offline use)",
        prefs.getString("style", DEFAULT),
        s -> {
          android.net.Uri uri = android.net.Uri.parse(s);
          if (!"https".equals(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException("Use an HTTPS style URL.");
          if (uri.getHost().endsWith("tile.openstreetmap.org"))
            throw new IllegalArgumentException("Do not use the public OSM tile server.");
          downloads.pause();
          prefs.edit().putString("style", s).apply();
          loadStyle();
        });
  }

  public boolean onActivityResult(int request, int result, Intent data) {
    return importer.onActivityResult(request, result, data);
  }

  public void onStart() {
    downloads.onStart();
    if (view != null) view.onStart();
  }

  public void onResume() {
    if (view != null) view.onResume();
  }

  public void onPause() {
    if (view != null) view.onPause();
  }

  public void onStop() {
    downloads.onStop();
    if (view != null) view.onStop();
  }

  public void onDestroy() {
    destroyed = true;
    downloads.onStop();
    worker.shutdownNow();
    importer.close();
    if (view != null) view.onDestroy();
  }

  public void onSaveInstanceState(Bundle b) {
    if (view != null) view.onSaveInstanceState(b);
  }

  private final class Grid extends View {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Grid() {
      super(a);
      setBackgroundColor(0xffedf0e8);
      setContentDescription("Coordinate grid with hunt find markers");
    }

    float x(Point p) {
      return getWidth() / 2f
          + (float) ((p.longitude - lon) * Math.cos(Math.toRadians(lat)) * 111320 / 2);
    }

    float y(Point p) {
      return getHeight() / 2f - (float) ((p.latitude - lat) * 111320 / 2);
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      paint.setColor(0xffbac6b4);
      paint.setStrokeWidth(1);
      for (int x = 0; x < getWidth(); x += 50) c.drawLine(x, 0, x, getHeight(), paint);
      for (int y = 0; y < getHeight(); y += 50) c.drawLine(0, y, getWidth(), y, paint);
      paint.setColor(Color.DKGRAY);
      paint.setTextSize(26);
      c.drawText(String.format(Locale.US, "%.5f, %.5f · grid ~100 m", lat, lon), 12, 32, paint);
      for (Point p : points) {
        paint.setColor(0xffa13c16);
        c.drawCircle(x(p), y(p), 12, paint);
        paint.setColor(Color.BLACK);
        c.drawText(p.title, x(p) + 14, y(p), paint);
      }
    }

    public boolean onTouchEvent(android.view.MotionEvent event) {
      if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
        for (Point p : points)
          if (Math.hypot(event.getX() - x(p), event.getY() - y(p)) < 32) {
            showPoint(p);
            break;
          }
        performClick();
        return true;
      }
      return true;
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
