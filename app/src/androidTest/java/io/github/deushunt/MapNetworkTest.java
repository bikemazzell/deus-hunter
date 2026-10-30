package io.github.deushunt;

import static org.junit.Assert.*;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.junit.Test;

/** Exercises the real interceptor with local responses. Never contacts a public provider. */
public class MapNetworkTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private final SharedPreferences prefs = context.getSharedPreferences("map-network-test", 0);

  private Response intercept(MapNetwork network, byte[] bytes, AtomicReference<Request> sent)
      throws IOException {
    return intercept(network, bytes, sent, false);
  }

  private Response intercept(
      MapNetwork network, byte[] bytes, AtomicReference<Request> sent, boolean fragmented)
      throws IOException {
    Request request = new Request.Builder().url("https://fixture.invalid/tile.pbf").build();
    Interceptor.Chain chain =
        (Interceptor.Chain)
            Proxy.newProxyInstance(
                Interceptor.Chain.class.getClassLoader(),
                new Class[] {Interceptor.Chain.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("request")) return request;
                  if (method.getName().equals("proceed")) {
                    Request outgoing = (Request) args[0];
                    sent.set(outgoing);
                    return new Response.Builder()
                        .request(outgoing)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(fragmented ? fragmentedBody(bytes) : ResponseBody.create(bytes, null))
                        .build();
                  }
                  throw new AssertionError("Unexpected chain method " + method.getName());
                });
    return network.client.networkInterceptors().get(0).intercept(chain);
  }

  private ResponseBody fragmentedBody(byte[] bytes) {
    okio.BufferedSource source =
        okio.Okio.buffer(
            new okio.Source() {
              int offset;

              public long read(okio.Buffer sink, long count) {
                if (offset == bytes.length) return -1;
                sink.writeByte(bytes[offset++]);
                return 1;
              }

              public okio.Timeout timeout() {
                return okio.Timeout.NONE;
              }

              public void close() {}
            });
    return new ResponseBody() {
      public MediaType contentType() {
        return null;
      }

      public long contentLength() {
        return -1;
      }

      public okio.BufferedSource source() {
        return source;
      }
    };
  }

  @Test
  public void shortEmptyAndFragmentedResponsesCountOnlyDeliveredBytes() throws Exception {
    long id = System.nanoTime();
    MapNetwork network = new MapNetwork(context, prefs, s -> {});
    network.begin(id);
    try (Response ignored =
        intercept(network, new byte[] {1, 2, 3}, new AtomicReference<>(), true)) {}
    assertEquals(3, prefs.getLong("bytes" + id, 0));
    try (Response ignored = intercept(network, new byte[0], new AtomicReference<>(), true)) {}
    assertEquals(3, prefs.getLong("bytes" + id, 0));
    try (Response ignored = intercept(network, new byte[] {4}, new AtomicReference<>())) {}
    assertEquals(4, prefs.getLong("bytes" + id, 0));
    network.end();
  }

  @Test
  public void identifiesClientAndRetainsQuotaAcrossResume() throws Exception {
    long id = System.nanoTime();
    MapNetwork network = new MapNetwork(context, prefs, s -> {});
    assertEquals(4, network.client.dispatcher().getMaxRequests());
    assertEquals(4, network.client.dispatcher().getMaxRequestsPerHost());
    AtomicReference<Request> sent = new AtomicReference<>();
    network.begin(id);
    try (Response response = intercept(network, new byte[] {1, 2, 3}, sent)) {
      assertArrayEquals(new byte[] {1, 2, 3}, response.body().bytes());
    }
    assertTrue(sent.get().header("User-Agent").startsWith("DeusHunter/"));
    assertEquals(1, prefs.getLong("requests" + id, 0));
    long bytes = prefs.getLong("bytes" + id, 0);
    assertTrue(bytes >= 3);
    network.end();
    MapNetwork resumed = new MapNetwork(context, prefs, s -> {});
    resumed.begin(id);
    try (Response ignored = intercept(resumed, new byte[] {4}, sent)) {}
    assertEquals(2, prefs.getLong("requests" + id, 0));
    assertTrue(prefs.getLong("bytes" + id, 0) > bytes);
    resumed.end();
  }

  @Test
  public void rejectsRequestAndByteOverrunBeforeReadingExtraBytes() throws Exception {
    long id = System.nanoTime();
    prefs.edit().putLong("requests" + id, 3000).commit();
    MapNetwork n = new MapNetwork(context, prefs, s -> {});
    n.begin(id);
    try {
      intercept(n, new byte[1], new AtomicReference<>());
      fail("Request quota");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("3,000"));
    }
    n.end();
    long byteId = id + 1;
    prefs.edit().putLong("bytes" + byteId, 49_999_990).commit();
    n.begin(byteId);
    try {
      intercept(n, new byte[20], new AtomicReference<>());
      fail("Byte quota");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("50 MB"));
    }
    assertEquals(50_000_000, prefs.getLong("bytes" + byteId, 0));
    n.end();
  }
}
