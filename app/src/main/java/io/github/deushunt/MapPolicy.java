package io.github.deushunt;

/** Conservative provider limits: count every HTTP resource, including non-tile resources. */
final class MapPolicy {
  static final long MAX_BYTES = 50_000_000, MIN_FREE = 100_000_000;
  static final int MAX_REQUESTS = 3000;

  static long tiles(double south, double west, double north, double east, int min, int max) {
    if (!Double.isFinite(south + west + north + east)
        || south < -85
        || north > 85
        || south >= north
        || west < -180
        || east > 180
        || west >= east
        || min < 0
        || max > 18
        || min > max)
      throw new IllegalArgumentException(
          "Use ordered bounds within ±85° latitude, ±180° longitude and zoom 0–18.");
    long count = 0;
    for (int z = min; z <= max; z++) {
      double n = 1L << z;
      long width =
          (long) Math.floor((east + 180) / 360 * n) - (long) Math.floor((west + 180) / 360 * n) + 1;
      long height = (long) Math.floor(y(south) * n) - (long) Math.floor(y(north) * n) + 1;
      count += width * height;
    }
    return count;
  }

  private static double y(double lat) {
    double r = Math.toRadians(lat);
    return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2;
  }

  static final class Budget {
    long requests, bytes;

    Budget(long requests, long bytes) {
      this.requests = requests;
      this.bytes = bytes;
    }

    synchronized boolean request() {
      if (requests >= MAX_REQUESTS) return false;
      requests++;
      return true;
    }

    synchronized boolean bytes(long n) {
      if (n < 0 || n > MAX_BYTES - bytes) return false;
      bytes += n;
      return true;
    }
  }
}
