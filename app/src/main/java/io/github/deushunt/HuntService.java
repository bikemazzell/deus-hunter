package io.github.deushunt;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;
import io.github.deusble.android.AndroidScanBackend;

/** Owns collection while the activity is absent. It never restarts a hunt silently. */
public class HuntService extends Service implements LocationListener {
  static final String STOP = "io.github.deushunt.STOP";
  static volatile String activeSession;
  static volatile String status = "Ready. Select a detector to begin.";
  private static final SessionGate gate = new SessionGate();

  static boolean isCollecting() {
    return gate.busy();
  }

  private volatile boolean destroyed;
  private String ownedSession;
  private HandlerThread thread;
  private Handler worker;
  private HuntStore store;
  private RecordingEngine engine;
  private LocationManager locations;

  @Override
  public void onCreate() {
    super.onCreate();
    thread = new HandlerThread("hunt-writer");
    thread.start();
    worker = new Handler(thread.getLooper());
    store = new HuntStore(this);
    locations = (LocationManager) getSystemService(LOCATION_SERVICE);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (STOP.equals(intent.getAction())) {
      finishSession();
      return START_NOT_STICKY;
    }
    if (destroyed) return START_NOT_STICKY;
    String address = intent.getStringExtra("detector"), resume = intent.getStringExtra("resume");
    if (address == null) {
      status = "Choose a detector first.";
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!gate.reserve(this)) return START_NOT_STICKY;
    try {
      showForeground();
    } catch (SecurityException | IllegalStateException e) {
      status = "Cannot start collection: " + e.getMessage();
      gate.release(this);
      stopSelf();
      return START_NOT_STICKY;
    }
    worker.post(
        () -> {
          if (destroyed) return;
          try {
            if (resume != null) {
              store.resume(resume);
              ownedSession = resume;
            } else ownedSession = store.startSession(address);
            activeSession = ownedSession;
            engine =
                new RecordingEngine(
                    store,
                    ownedSession,
                    address,
                    createBackend(),
                    s -> status = s,
                    command -> worker.post(command));
            engine.setGrouping(getSharedPreferences("hunt", 0).getBoolean("grouping", true));
            engine.start();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                && locations != null) {
              for (String provider : locations.getProviders(true))
                if (provider.equals(LocationManager.GPS_PROVIDER)
                    || provider.equals(LocationManager.NETWORK_PROVIDER))
                  locations.requestLocationUpdates(provider, 1000, 0, this, thread.getLooper());
            }
          } catch (RuntimeException e) {
            status = "Collection failed: " + e.getMessage();
            cleanupCollection(false);
            stopSelf();
          }
        });
    return START_NOT_STICKY;
  }

  protected io.github.deusble.android.ScanBackend createBackend() {
    return new AndroidScanBackend(this);
  }

  private void showForeground() {
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    manager.createNotificationChannel(
        new NotificationChannel("hunt", "Active hunt", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, getClass()).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification =
        new Notification.Builder(this, "hunt")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Deus Hunter is collecting")
            .setContentText("Open for status. Stop ends this session.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
            .build();
    if (Build.VERSION.SDK_INT >= 29) {
      int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
      if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
          == PackageManager.PERMISSION_GRANTED)
        type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
      startForeground(1, notification, type);
    } else startForeground(1, notification);
  }

  private void finishSession() {
    if (!gate.stop(this)) {
      if (!gate.busy()) stopSelf();
      return;
    }
    worker.post(
        () ->
            ServiceCleanup.run(
                this::cleanupError,
                () -> cleanupCollection(true),
                () -> stopForeground(STOP_FOREGROUND_REMOVE),
                this::stopSelf,
                () -> gate.release(this)));
  }

  private void cleanupError(String message) {
    status = "Collection stopped; cleanup failed: " + message + ". Saved records remain available.";
  }

  /** Runs only on the writer thread. Each service changes only its own session. */
  private void cleanupCollection(boolean ended) {
    boolean hadSession = ownedSession != null;
    boolean[] failed = {false};
    ServiceCleanup.run(
        message -> {
          failed[0] = true;
          cleanupError(message);
        },
        () -> {
          RecordingEngine previous = engine;
          engine = null;
          if (previous != null) previous.stop();
        },
        () -> {
          if (locations != null) locations.removeUpdates(this);
        },
        () -> {
          if (ownedSession == null) return;
          if (ended) {
            store.stopSession(ownedSession);
          } else {
            ContentValues values = new ContentValues();
            values.put("state", "interrupted");
            store
                .getWritableDatabase()
                .update("sessions", values, "id=? AND state='active'", new String[] {ownedSession});
          }
        },
        () -> {
          if (ownedSession != null && ownedSession.equals(activeSession)) activeSession = null;
          ownedSession = null;
        });
    if (ended && hadSession && !failed[0])
      status = "Session ended. All committed records remain saved.";
  }

  @Override
  public void onLocationChanged(Location location) {
    if (engine != null)
      engine.location(
          new Fix(
              location.getLatitude(),
              location.getLongitude(),
              location.hasAccuracy() ? location.getAccuracy() : Double.POSITIVE_INFINITY,
              location.getElapsedRealtimeNanos(),
              location.hasAltitude() ? location.getAltitude() : null,
              location.hasSpeed() ? (double) location.getSpeed() : null));
  }

  @Override
  public void onProviderDisabled(String provider) {
    status = "GPS provider disabled; BLE collection continues with unlocated observations.";
  }

  @Override
  public void onProviderEnabled(String provider) {}

  @Override
  public void onStatusChanged(String provider, int state, Bundle extras) {}

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    gate.stop(this);
    if (worker != null)
      worker.post(
          () ->
              ServiceCleanup.run(
                  this::cleanupError,
                  () -> cleanupCollection(false),
                  () -> store.close(),
                  () -> gate.release(this),
                  () -> thread.quitSafely()));
    else gate.release(this);
    super.onDestroy();
  }
}
