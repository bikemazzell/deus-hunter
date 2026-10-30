package io.github.deushunt;

import static org.junit.Assert.*;

import org.junit.Test;

public class SessionGateTest {
  @Test
  public void reservesBeforeWorkAndRejectsStartsUntilStopFinishes() {
    SessionGate gate = new SessionGate();
    Object first = new Object(), next = new Object();
    assertTrue(gate.reserve(first));
    assertTrue(gate.busy());
    assertFalse(gate.reserve(first));
    assertFalse(gate.reserve(next));
    assertTrue(gate.stop(first));
    assertFalse(gate.stop(first));
    assertFalse(gate.reserve(next));
    gate.release(first);
    assertFalse(gate.busy());
    assertTrue(gate.reserve(next));
    gate.release(first);
    assertTrue(gate.busy());
    gate.release(next);
    assertFalse(gate.busy());
  }
}
