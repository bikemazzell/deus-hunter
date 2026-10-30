package io.github.deushunt;

import static org.junit.Assert.*;

import org.junit.Test;

public class MapPolicyTest {
  static void invalid(Runnable r) {
    try {
      r.run();
      fail("Expected invalid region");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void rejectsHugeRegionAndInvalidCoordinates() {
    assertTrue(MapPolicy.tiles(51, 0, 51.001, 0.001, 12, 14) < 3000);
    assertTrue(MapPolicy.tiles(-80, -179, 80, 179, 0, 18) > 3000);
    invalid(() -> MapPolicy.tiles(90, 0, 91, 1, 1, 2));
    invalid(() -> MapPolicy.tiles(0, 0, 1, 1, 12, 2));
  }

  @Test
  public void rejectsDatelineAndNonFiniteBounds() {
    invalid(() -> MapPolicy.tiles(0, 179, 1, -179, 1, 2));
    invalid(() -> MapPolicy.tiles(Double.NaN, 0, 1, 1, 1, 2));
    invalid(() -> MapPolicy.tiles(0, 0, 1, 1, 0, 19));
    assertEquals(1, MapPolicy.tiles(0, 0, 1, 1, 0, 0));
  }

  @Test
  public void simultaneousReservationsCannotExceedQuota() throws Exception {
    MapPolicy.Budget b = new MapPolicy.Budget(0, 0);
    Thread[] threads = new Thread[4];
    for (int i = 0; i < threads.length; i++)
      threads[i] =
          new Thread(
              () -> {
                for (int j = 0; j < 2000; j++) {
                  b.request();
                  b.bytes(10000);
                }
              });
    for (Thread t : threads) t.start();
    for (Thread t : threads) t.join();
    assertEquals(3000, b.requests);
    assertEquals(50_000_000, b.bytes);
    assertFalse(b.bytes(Long.MAX_VALUE));
    assertFalse(b.bytes(-1));
  }

  @Test
  public void budgetSurvivesResumeAndRejectsBeforeOverrun() {
    MapPolicy.Budget b = new MapPolicy.Budget(2999, 49_999_990);
    assertTrue(b.request());
    assertFalse(b.request());
    assertTrue(b.bytes(10));
    assertFalse(b.bytes(1));
    assertEquals(50_000_000, b.bytes);
  }
}
