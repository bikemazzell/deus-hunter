package io.github.deushunt;

import static org.junit.Assert.*;

import org.junit.Test;

public class FixTest {
  @Test
  public void invalidCoordinatesAndAccuracyRemainUnlocated() {
    assertFalse(new Fix(Double.NaN, 0, 3, 1, null, null).valid());
    assertFalse(new Fix(91, 0, 3, 1, null, null).valid());
    assertFalse(new Fix(0, 181, 3, 1, null, null).valid());
    assertFalse(new Fix(0, 0, -1, 1, null, null).valid());
    assertFalse(new Fix(0, 0, Double.POSITIVE_INFINITY, 1, null, null).valid());
    assertTrue(new Fix(0, 0, 0, 1, null, null).valid());
  }

  @Test
  public void invalidOptionalValuesAreUnknown() {
    Fix f = new Fix(0, 0, 3, 1, Double.NaN, -1.0);
    assertNull(f.altitude);
    assertNull(f.speed);
  }
}
