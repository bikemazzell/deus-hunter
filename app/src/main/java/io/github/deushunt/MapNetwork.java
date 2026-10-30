package io.github.deushunt;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import okhttp3.*;

/** One bounded HTTP client for ambient resources and explicit offline downloads. */
final class MapNetwork {
  private static final ConcurrentHashMap<Long, MapPolicy.Budget> BUDGETS =
      new ConcurrentHashMap<>();
  final OkHttpClient client;
  private final SharedPreferences prefs;
  private final File files;
  private final WeakReference<Consumer<String>> listener;
  private volatile Session session;
  private volatile long epoch;

  private static final class Session {
    final long id;
    final MapPolicy.Budget budget;

    Session(long id, MapPolicy.Budget budget) {
      this.id = id;
      this.budget = budget;
    }
  }

  MapNetwork(Context context, SharedPreferences prefs, Consumer<String> listener) {
    this.prefs = prefs;
    this.files = context.getFilesDir();
    this.listener = new WeakReference<>(listener);
    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequests(4);
    dispatcher.setMaxRequestsPerHost(4);
    client =
        new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(
                chain -> {
                  long requestEpoch = epoch;
                  try {
                    return chain.proceed(chain.request());
                  } catch (IOException e) {
                    new Handler(Looper.getMainLooper())
                        .post(
                            () -> {
                              Consumer<String> callback = MapNetwork.this.listener.get();
                              if (callback != null && requestEpoch == epoch)
                                callback.accept(
                                    "Map resource unavailable. Coordinate grid is available. "
                                        + e.getMessage());
                            });
                    throw e;
                  }
                })
            .addNetworkInterceptor(
                chain -> {
                  if (files.getUsableSpace() < MapPolicy.MIN_FREE)
                    throw new IOException("Map storage paused: less than 100 MB free.");
                  String host = chain.request().url().host();
                  if (host.equals("tile.openstreetmap.org")
                      || host.endsWith(".tile.openstreetmap.org"))
                    throw new IOException(
                        "Public OSM tile server is not supported for persistent/offline use.");
                  Session current = session;
                  if (current != null) {
                    if (!current.budget.request())
                      throw quota(current, "3,000-resource limit reached; use a smaller region.");
                    persist(current);
                  }
                  Request request =
                      chain
                          .request()
                          .newBuilder()
                          .header(
                              "User-Agent",
                              "DeusHunter/0.1 (Android; github.com/bikemazzell/deus-hunter)")
                          .build();
                  try (Response response = chain.proceed(request)) {
                    if (response.body() == null)
                      return response
                          .newBuilder()
                          .body(ResponseBody.create(new byte[0], null))
                          .build();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    try (InputStream in = response.body().byteStream()) {
                      long expected = response.body().contentLength(), delivered = 0;
                      while (true) {
                        if (expected >= 0 && delivered >= expected) break;
                        int allowed =
                            expected >= 0
                                ? (int) Math.min(buffer.length, expected - delivered)
                                : buffer.length;
                        if (current != null)
                          synchronized (current.budget) {
                            allowed =
                                (int) Math.min(allowed, MapPolicy.MAX_BYTES - current.budget.bytes);
                            if (allowed <= 0)
                              throw quota(
                                  current, "50 MB download limit reached; use a smaller region.");
                            current.budget.bytes(allowed);
                            persist(current);
                          }
                        int n;
                        try {
                          n = in.read(buffer, 0, allowed);
                        } catch (IOException | RuntimeException e) {
                          if (current != null) refund(current, allowed);
                          throw e;
                        }
                        if (current != null) refund(current, allowed - Math.max(n, 0));
                        if (n < 0) break;
                        delivered += n;
                        out.write(buffer, 0, n);
                        if (out.size() > MapPolicy.MAX_BYTES)
                          throw new IOException("Map resource exceeds 50 MB.");
                      }
                    }
                    return response
                        .newBuilder()
                        .body(ResponseBody.create(out.toByteArray(), response.body().contentType()))
                        .build();
                  }
                })
            .build();
  }

  void begin(long id) {
    epoch++;
    session =
        new Session(
            id,
            BUDGETS.computeIfAbsent(
                id,
                k ->
                    new MapPolicy.Budget(
                        prefs.getLong("requests" + id, 0), prefs.getLong("bytes" + id, 0))));
  }

  void end() {
    epoch++;
    session = null;
  }

  private void persist(Session s) throws IOException {
    synchronized (s.budget) {
      if (!prefs
          .edit()
          .putLong("requests" + s.id, s.budget.requests)
          .putLong("bytes" + s.id, s.budget.bytes)
          .commit()) throw new IOException("Cannot save map download quota.");
    }
  }

  private void refund(Session current, int unread) throws IOException {
    if (unread == 0) return;
    synchronized (current.budget) {
      current.budget.bytes -= unread;
      persist(current);
    }
  }

  private IOException quota(Session s, String text) {
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              Consumer<String> callback = MapNetwork.this.listener.get();
              if (session == s && callback != null) callback.accept(text);
            });
    return new IOException(text);
  }
}
