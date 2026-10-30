package io.github.deushunt;

/** Reserves collection before asynchronous startup and keeps ownership through cleanup. */
final class SessionGate {
  private Object owner;
  private boolean stopping;

  synchronized boolean reserve(Object candidate) {
    if (owner != null) return false;
    owner = candidate;
    stopping = false;
    return true;
  }

  synchronized boolean stop(Object candidate) {
    if (owner != candidate || stopping) return false;
    stopping = true;
    return true;
  }

  synchronized void release(Object candidate) {
    if (owner != candidate) return;
    owner = null;
    stopping = false;
  }

  synchronized boolean busy() {
    return owner != null;
  }
}
