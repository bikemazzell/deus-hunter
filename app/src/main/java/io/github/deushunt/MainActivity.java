package io.github.deushunt;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.github.deusble.android.*;
import io.github.deusble.core.Observation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private HuntStore store;
  private MapController maps;
  private TextView status, selected;
  private final Handler timer = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private DetectorScanner discovery;
  private String detector;
  private boolean csv;
  private boolean alive;
  private int lastCount = -1;
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (!alive) return;
          update();
          timer.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    csv = state != null && state.getBoolean("export_csv");
    alive = true;
    store = new HuntStore(this);
    try {
      if (!HuntService.isCollecting()) store.recover();
    } catch (RuntimeException error) {
      HuntService.status =
          "Storage unavailable: " + error.getMessage() + ". Collection is not active.";
    }
    detector = getSharedPreferences("hunt", 0).getString("detector", null);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xfff7f4ec);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
          } else {
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          }
          return insets;
        });
    TextView title = new TextView(this);
    title.setText(R.string.app_title);
    title.setTextSize(22);
    title.setPadding(16, 8, 16, 0);
    root.addView(title);
    selected = new TextView(this);
    selected.setPadding(16, 2, 16, 2);
    root.addView(selected);
    LinearLayout controls = new LinearLayout(this);
    button(controls, "Detector", this::discover);
    button(controls, "Start", () -> start(null));
    button(
        controls,
        "Stop",
        () -> {
          startService(new Intent(this, HuntService.class).setAction(HuntService.STOP));
        });
    root.addView(controls);
    FrameLayout map = new FrameLayout(this);
    root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
    status = new TextView(this);
    status.setPadding(12, 6, 12, 6);
    status.setTextSize(13);
    root.addView(status);
    LinearLayout navigation = new LinearLayout(this);
    button(navigation, "Maps", () -> maps.showSettings());
    button(navigation, "Records", () -> browser().records());
    button(navigation, "History", () -> browser().history());
    button(navigation, "More", this::more);
    root.addView(navigation);
    setContentView(root);
    maps = new MapController(this, map);
    maps.onCreate(state);
    maps.setOnPointClick(id -> browser().showFind(id));
    centerFromKnownGps(false);
    update();
  }

  private void button(LinearLayout row, String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(12);
    b.setPadding(2, 0, 2, 0);
    b.setOnClickListener(
        v -> {
          try {
            action.run();
          } catch (RuntimeException error) {
            message(
                "Action failed: " + error.getMessage() + ". Saved records have not been deleted.");
          }
        });
    row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private void update() {
    selected.setText(
        detector == null
            ? getString(R.string.no_detector)
            : getString(
                R.string.selected_detector,
                detector,
                !HuntService.isCollecting() ? "" : getString(R.string.active_suffix)));
    try {
      int count = store.count("observations");
      status.setText(
          getString(
              R.string.collection_status, HuntService.status, count, store.count("receipts")));
      if (count != lastCount) {
        lastCount = count;
        showPoints();
      }
    } catch (RuntimeException e) {
      status.setText(getString(R.string.storage_error, e.getMessage()));
    }
  }

  private void showPoints() {
    List<MapController.Point> points = new ArrayList<>();
    try (Cursor c =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT"
                    + " f.id,f.latitude,f.longitude,f.target_id,f.note,count(o.id),max(o.soil),sum(o.repeat_count)"
                    + " FROM finds f JOIN observations o ON o.find_id=f.id GROUP BY f.id",
                null)) {
      while (c.moveToNext())
        points.add(
            new MapController.Point(
                c.getString(0),
                c.getDouble(1),
                c.getDouble(2),
                "ID " + c.getInt(3),
                c.getInt(5)
                    + " detections · "
                    + c.getInt(7)
                    + " receipts\nSoil "
                    + c.getInt(6)
                    + "\n"
                    + c.getString(4)
                    + "\nGPS proximity does not prove one object."));
    }
    maps.showPoints(points);
  }

  private boolean permissions() {
    List<String> missing = new ArrayList<>();
    for (String p : required())
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
    if (missing.isEmpty()) return true;
    requestPermissions(missing.toArray(new String[0]), 10);
    return false;
  }

  private List<String> required() {
    List<String> p =
        new ArrayList<>(
            Arrays.asList(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION));
    if (Build.VERSION.SDK_INT >= 31) {
      p.add(Manifest.permission.BLUETOOTH_SCAN);
      p.add(Manifest.permission.BLUETOOTH_CONNECT);
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
    return p;
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] p, int[] results) {
    super.onRequestPermissionsResult(request, p, results);
    HuntService.status =
        "Permission choice saved. Tap Detector or Start again. BLE needs precise location and"
            + " Bluetooth permission; a GPS fix is optional.";
    update();
  }

  private boolean scanPermissions() {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) return false;
    return Build.VERSION.SDK_INT < 31
        || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED);
  }

  private void discover() {
    if (HuntService.isCollecting()) {
      message("Detector selection is fixed during a session.");
      return;
    }
    if (!scanPermissions()) {
      permissions();
      return;
    }
    ArrayList<String> addresses = new ArrayList<>(), labels = new ArrayList<>();
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
    ListView list = new ListView(this);
    list.setAdapter(adapter);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Nearby Deus candidates")
            .setMessage("Confirm the address belongs to your detector.")
            .setView(list)
            .setNegativeButton("Close", null)
            .setNeutralButton("Enter address", (d, w) -> enterAddress())
            .create();
    discovery =
        new DetectorScanner(
            new AndroidScanBackend(this),
            new DetectorScanner.Listener() {
              @Override
              public void onDevice(Observation o) {
                runOnUiThread(
                    () -> {
                      if (!addresses.contains(o.address())) {
                        addresses.add(o.address());
                        labels.add(
                            (o.name() == null ? "Unnamed" : o.name())
                                + "\n"
                                + o.address()
                                + " · "
                                + o.rssi()
                                + " dBm");
                        adapter.notifyDataSetChanged();
                      }
                    });
              }

              @Override
              public void onError(DetectorScanner.Error error, int code) {
                runOnUiThread(
                    () -> {
                      HuntService.status =
                          "Discovery unavailable: "
                              + error
                              + " ("
                              + code
                              + "). Check Bluetooth and permissions.";
                      dialog.setMessage(HuntService.status);
                      update();
                    });
              }
            });
    list.setOnItemClickListener(
        (parent, v, position, id) -> {
          select(addresses.get(position));
          dialog.dismiss();
        });
    dialog.setOnDismissListener(
        d -> {
          if (discovery != null) {
            discovery.stop();
            discovery = null;
          }
        });
    dialog.show();
    discovery.start();
  }

  private void enterAddress() {
    EditText input = new EditText(this);
    input.setHint("AA:BB:CC:DD:EE:FF");
    new AlertDialog.Builder(this)
        .setTitle("Detector address")
        .setView(input)
        .setPositiveButton(
            "Select",
            (d, w) -> {
              String address = input.getText().toString().trim().toUpperCase(Locale.ROOT);
              if (!address.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                message("Enter a Bluetooth address with six pairs of hex digits.");
                return;
              }
              select(address);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void select(String address) {
    if (HuntService.isCollecting()) {
      message("End the current session before changing detectors.");
      return;
    }
    detector = address;
    getSharedPreferences("hunt", 0).edit().putString("detector", address).apply();
    update();
  }

  private void start(String resume) {
    if (HuntService.isCollecting()) {
      message("A session is already active.");
      return;
    }
    if (detector == null) {
      discover();
      return;
    }
    if (!scanPermissions()) {
      permissions();
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
    if (discovery != null) {
      discovery.stop();
      discovery = null;
    }
    Intent intent = new Intent(this, HuntService.class).putExtra("detector", detector);
    if (resume != null) intent.putExtra("resume", resume);
    try {
      startForegroundService(intent);
    } catch (RuntimeException e) {
      message("Cannot start session: " + e.getMessage());
    }
  }

  private RecordBrowser browser() {
    return new RecordBrowser(
        this,
        store,
        () -> {
          lastCount = -1;
          update();
        },
        (address, id) -> {
          select(address);
          start(id);
        });
  }

  private void more() {
    boolean grouping = getSharedPreferences("hunt", 0).getBoolean("grouping", true);
    new AlertDialog.Builder(this)
        .setTitle("Tools")
        .setItems(
            new String[] {
              "Export JSONL (all evidence)",
              "Export CSV (detections)",
              "Grouping: " + (grouping ? "on" : "off"),
              "Center on GPS",
              "About"
            },
            (d, w) -> {
              if (w < 2) {
                csv = w == 1;
                Intent intent =
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(csv ? "text/csv" : "application/x-ndjson")
                        .putExtra(
                            Intent.EXTRA_TITLE,
                            "deus-hunter-"
                                + System.currentTimeMillis()
                                + (csv ? ".csv" : ".jsonl"));
                startActivityForResult(intent, 20);
              } else if (w == 2) {
                if (HuntService.isCollecting()) {
                  message(
                      "Change grouping between sessions. You can split saved detections at any"
                          + " time.");
                  return;
                }
                getSharedPreferences("hunt", 0).edit().putBoolean("grouping", !grouping).apply();
                message(
                    "Grouping "
                        + (!grouping ? "enabled" : "disabled")
                        + " for future sessions. Existing associations remain editable.");
              } else if (w == 3) gps();
              else
                message(
                    "Deus Hunter stores detector evidence locally. Target ID is not a metal"
                        + " classification. RSSI is radio signal strength, not target depth. A map"
                        + " or GPS fix is optional. BLE requires precise location permission on"
                        + " this library. No cloud account or paid service is required.");
            })
        .show();
  }

  private void gps() {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          12);
      return;
    }
    centerFromKnownGps(true);
  }

  private void centerFromKnownGps(boolean reportMissing) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) return;
    LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
    Location best = null;
    for (String provider : manager.getProviders(true)) {
      Location location = manager.getLastKnownLocation(provider);
      if (location != null
          && (best == null || location.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos()))
        best = location;
    }
    if (best == null) {
      if (reportMissing)
        message("No GPS fix yet. Enter coordinates from Maps, or try again outdoors.");
      return;
    }
    maps.center(best.getLatitude(), best.getLongitude());
  }

  private void message(String text) {
    new AlertDialog.Builder(this).setMessage(text).setPositiveButton("Close", null).show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (maps.onActivityResult(request, result, data)) return;
    if (request != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
    boolean format = csv;
    io.execute(
        () -> {
          try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (stream == null) throw new IOException("Cannot open destination");
            try (Writer writer =
                    new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
                HuntStore exportStore = new HuntStore(this)) {
              exportStore.export(writer, format);
            }
            runOnUiThread(
                () -> {
                  if (alive) message("Export saved. Source records remain on this device.");
                });
          } catch (IOException | RuntimeException e) {
            runOnUiThread(
                () -> {
                  if (alive)
                    message(
                        "Export failed: "
                            + e.getMessage()
                            + ". Source records remain saved. The destination may contain a partial"
                            + " file.");
                });
          }
        });
  }

  @Override
  protected void onStart() {
    super.onStart();
    maps.onStart();
  }

  @Override
  protected void onResume() {
    super.onResume();
    maps.onResume();
    timer.post(refresh);
  }

  @Override
  protected void onPause() {
    timer.removeCallbacks(refresh);
    maps.onPause();
    super.onPause();
  }

  @Override
  protected void onStop() {
    maps.onStop();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    maps.onSaveInstanceState(state);
    state.putBoolean("export_csv", csv);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    timer.removeCallbacks(refresh);
    if (discovery != null) discovery.stop();
    maps.onDestroy();
    store.close();
    io.shutdown();
    super.onDestroy();
  }
}
