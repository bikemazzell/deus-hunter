package io.github.deushunt;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import org.junit.Test;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.module.http.HttpRequestImpl;
import org.maplibre.android.offline.*;
import org.maplibre.android.storage.FileSource;

/** Real SDK database and lifecycle tests using a generated local style; no public downloads. */
public class MapOfflineTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();

  private static Object field(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(owner);
  }

  @Test
  public void regionSurvivesStopAndValidDatabaseImportsWithoutNetwork() throws Exception {
    File style = new File(context.getFilesDir(), "map-test-style.json");
    Files.write(
        style.toPath(),
        ("{\"version\":8,\"sources\":{\"fixture\":{\"type\":\"geojson\",\"data\":{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[0.5,0.5]},\"properties\":{}}}},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#8cb369\"}},{\"id\":\"fixture\",\"type\":\"circle\",\"source\":\"fixture\",\"paint\":{\"circle-radius\":10,\"circle-color\":\"#cc3300\"}}]}")
            .getBytes(StandardCharsets.UTF_8));
    final String styleUrl = "https://fixture.invalid/offline-style-" + System.nanoTime() + ".json";
    SharedPreferences prefs = context.getSharedPreferences("maps", 0);
    String previous = prefs.getString("style", null);
    prefs.edit().putString("style", styleUrl).commit();
    Activity activity = null;
    OfflineRegion region = null;
    try {
      Intent intent =
          new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = instrumentation.startActivitySync(intent);
      final Activity a = activity;
      MapController controller = (MapController) field(a, "maps");
      MapDownloads downloads = (MapDownloads) field(controller, "downloads");
      byte[] styleBytes = Files.readAllBytes(style.toPath());
      AtomicInteger requests = new AtomicInteger();
      HttpRequestImpl.setOkHttpClient(
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain -> {
                    if (!chain.request().url().host().equals("fixture.invalid"))
                      throw new IOException("Unexpected fixture request");
                    requests.incrementAndGet();
                    return new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "max-age=31536000")
                        .body(ResponseBody.create(styleBytes, MediaType.parse("application/json")))
                        .build();
                  })
              .build());

      AtomicReference<OfflineRegion> created = new AtomicReference<>();
      AtomicReference<String> error = new AtomicReference<>();
      CountDownLatch creation = new CountDownLatch(1);
      instrumentation.runOnMainSync(
          () ->
              OfflineManager.getInstance(context)
                  .createOfflineRegion(
                      new OfflineTilePyramidRegionDefinition(
                          styleUrl, LatLngBounds.from(1, 1, 0, 0), 0, 0, 1, false),
                      "local integration fixture".getBytes(StandardCharsets.UTF_8),
                      new OfflineManager.CreateOfflineRegionCallback() {
                        public void onCreate(OfflineRegion r) {
                          created.set(r);
                          creation.countDown();
                        }

                        public void onError(String e) {
                          error.set(e);
                          creation.countDown();
                        }
                      }));
      assertTrue("region creation callback", creation.await(15, TimeUnit.SECONDS));
      assertNull(error.get());
      region = created.get();
      assertNotNull(region);
      final OfflineRegion r = region;
      Method start = MapDownloads.class.getDeclaredMethod("start", OfflineRegion.class);
      start.setAccessible(true);
      instrumentation.runOnMainSync(
          () -> {
            downloads.onStop();
            try {
              start.invoke(downloads, r);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      AtomicReference<OfflineRegionStatus> status = new AtomicReference<>();
      CountDownLatch checked = new CountDownLatch(1);
      instrumentation.runOnMainSync(
          () ->
              r.getStatus(
                  new OfflineRegion.OfflineRegionStatusCallback() {
                    public void onStatus(OfflineRegionStatus s) {
                      status.set(s);
                      checked.countDown();
                    }

                    public void onError(String e) {
                      error.set(e);
                      checked.countDown();
                    }
                  }));
      assertTrue(checked.await(10, TimeUnit.SECONDS));
      assertNull(error.get());
      assertEquals(OfflineRegion.STATE_INACTIVE, status.get().getDownloadState());
      CountDownLatch completed = new CountDownLatch(1);
      instrumentation.runOnMainSync(
          () -> {
            r.setObserver(
                new OfflineRegion.OfflineRegionObserver() {
                  public void onStatusChanged(OfflineRegionStatus s) {
                    if (s.isComplete()) {
                      r.setDownloadState(OfflineRegion.STATE_INACTIVE);
                      completed.countDown();
                    }
                  }

                  public void onError(OfflineRegionError e) {
                    error.set(e.getMessage());
                    completed.countDown();
                  }

                  public void mapboxTileCountLimitExceeded(long n) {
                    error.set("quota");
                    completed.countDown();
                  }
                });
            r.setDownloadState(OfflineRegion.STATE_ACTIVE);
          });
      assertTrue("local style region completes", completed.await(20, TimeUnit.SECONDS));
      assertNull(error.get());
      assertTrue("SDK fetched the generated style", requests.get() > 0);
      CountDownLatch cachedStyle = new CountDownLatch(1);
      AtomicInteger unexpectedNetwork = new AtomicInteger();
      HttpRequestImpl.setOkHttpClient(
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain -> {
                    unexpectedNetwork.incrementAndGet();
                    throw new IOException("Test is offline");
                  })
              .build());
      MapLibreMap rendered = (MapLibreMap) field(controller, "map");
      org.maplibre.android.maps.MapView mapView =
          (org.maplibre.android.maps.MapView) field(controller, "view");
      CountDownLatch frameReady = new CountDownLatch(1);
      instrumentation.runOnMainSync(
          () ->
              mapView.addOnDidFinishRenderingFrameListener(
                  (full, e, rtime) -> {
                    if (full
                        && rendered.getStyle() != null
                        && rendered.getStyle().getSource("fixture") != null) frameReady.countDown();
                  }));
      instrumentation.runOnMainSync(
          () -> {mapView.setVisibility(android.view.View.VISIBLE);rendered.setStyle(styleUrl, loaded -> cachedStyle.countDown());});
      assertTrue("cached style loads without network", cachedStyle.await(10, TimeUnit.SECONDS));
      assertEquals(0, unexpectedNetwork.get());
      instrumentation.runOnMainSync(() -> assertNotNull(rendered.getStyle().getSource("fixture")));
      assertTrue("cached style frame finishes", frameReady.await(10, TimeUnit.SECONDS));
      CountDownLatch snapshotReady = new CountDownLatch(1);
      AtomicReference<android.graphics.Bitmap> screenshot = new AtomicReference<>();
      instrumentation.runOnMainSync(
          () -> {
            mapView.setVisibility(android.view.View.VISIBLE);
            rendered.snapshot(
                bitmap -> {
                  screenshot.set(bitmap);
                  snapshotReady.countDown();
                });
          });
      assertTrue("cached map renders", snapshotReady.await(10, TimeUnit.SECONDS));
      assertNotNull(screenshot.get());
      int pixel = screenshot.get().getPixel(10, 10);
      assertTrue(
          "generated map background is visible: " + Integer.toHexString(pixel),
          Math.abs(android.graphics.Color.green(pixel) - 179) < 10);
      File directory = new File(FileSource.getResourcesCachePath(context));
      File[] files = directory.listFiles((dir, name) -> name.endsWith(".db"));
      assertNotNull("SDK cache directory", files);
      File database = null;
      for (File candidate : files)
        if (candidate.getName().contains("offline")) database = candidate;
      assertNotNull(
          "SDK offline database in " + directory + " files=" + java.util.Arrays.toString(files),
          database);
      File copy = new File(context.getCacheDir(), "map-test-import.db");
      Files.copy(
          database.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      CountDownLatch imported = new CountDownLatch(1);
      AtomicReference<String> outcome = new AtomicReference<>();
      MapImporter importer =
          new MapImporter(
              a,
              OfflineManager.getInstance(context),
              text -> {
                if (text.startsWith("Imported")
                    || text.contains("failed")
                    || text.startsWith("Invalid")) {
                  outcome.set(text);
                  imported.countDown();
                }
              });
      instrumentation.runOnMainSync(
          () ->
              assertTrue(
                  importer.onActivityResult(
                      7412, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(copy)))));
      assertTrue("SDK import callback", imported.await(20, TimeUnit.SECONDS));
      assertTrue(outcome.get(), outcome.get().startsWith("Imported"));
      importer.close();
      copy.delete();
      // Existing region remains addressable after import; import never resets the database.
      CountDownLatch listed = new CountDownLatch(1);
      AtomicBoolean retained = new AtomicBoolean();
      instrumentation.runOnMainSync(
          () ->
              OfflineManager.getInstance(context)
                  .listOfflineRegions(
                      new OfflineManager.ListOfflineRegionsCallback() {
                        public void onList(OfflineRegion[] all) {
                          for (OfflineRegion item : all)
                            if (item.getId() == r.getId()) retained.set(true);
                          listed.countDown();
                        }

                        public void onError(String e) {
                          listed.countDown();
                        }
                      }));
      assertTrue(listed.await(10, TimeUnit.SECONDS));
      assertTrue(retained.get());
    } finally {
      if (region != null) {
        OfflineRegion remove = region;
        instrumentation.runOnMainSync(
            () ->
                remove.delete(
                    new OfflineRegion.OfflineRegionDeleteCallback() {
                      public void onDelete() {}

                      public void onError(String e) {}
                    }));
      }
      if (activity != null) {
        Activity finish = activity;
        instrumentation.runOnMainSync(finish::finish);
      }
      if (previous == null) prefs.edit().remove("style").commit();
      else prefs.edit().putString("style", previous).commit();
      HttpRequestImpl.setOkHttpClient(null);
      style.delete();
    }
  }
}
