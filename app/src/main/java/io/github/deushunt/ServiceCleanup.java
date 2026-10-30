package io.github.deushunt;

import java.util.function.Consumer;

/** Attempt every release step, even when a database or platform operation fails. */
final class ServiceCleanup {
  private ServiceCleanup() {}

  static void run(Consumer<String> report, Runnable... steps) {
    for (Runnable step : steps) {
      try {
        step.run();
      } catch (RuntimeException error) {
        report.accept(
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
      }
    }
  }
}
