package io.github.deushunt;

import static org.junit.Assert.*;

import org.junit.Test;

public class GeoRulesTest {
  @Test
  public void matchesOnlyRecentPastFix() {
    assertTrue(GeoRules.recent(11_000_000_000L, 1_000_000_000L));
    assertFalse(GeoRules.recent(11_000_000_001L, 1_000_000_000L));
    assertFalse(GeoRules.recent(1, 2));
  }

  @Test
  public void proximityRequiresAccurateCompatibleTargets() {
    assertTrue(GeoRules.group(51, 0, 10, 50, 51, 0, 10, 55));
    assertFalse(GeoRules.group(51, 0, 11, 50, 51, 0, 10, 50));
    assertFalse(GeoRules.group(51, 0, 1, 50, 51, 0, 1, 56));
    assertFalse(GeoRules.group(51, 0, 1, 50, 51.00004, 0, 1, 50));
  }
}
