package io.github.deushunt;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ServiceCleanupTest {
  @Test
  public void failureDoesNotSkipScannerLocationStateDatabaseOrThreadCleanup() {
    List<String> actions = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    ServiceCleanup.run(
        errors::add,
        () -> actions.add("scanner"),
        () -> actions.add("location"),
        () -> {
          throw new IllegalStateException("disk full");
        },
        () -> actions.add("state"),
        () -> actions.add("database"),
        () -> actions.add("thread"));
    assertEquals(
        java.util.Arrays.asList("scanner", "location", "state", "database", "thread"), actions);
    assertEquals(java.util.Collections.singletonList("disk full"), errors);
  }
}
