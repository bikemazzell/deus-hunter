package io.github.deushunt;

final class GeoRules {
  static boolean recent(long observation, long fix) {
    return fix >= 0 && observation >= fix && observation - fix <= 10_000_000_000L;
  }

  static boolean group(
      double lat,
      double lon,
      double accuracy,
      int target,
      double anchorLat,
      double anchorLon,
      double anchorAccuracy,
      int anchorTarget) {
    return accuracy <= 10
        && anchorAccuracy <= 10
        && Math.abs(target - anchorTarget) <= 5
        && meters(lat, lon, anchorLat, anchorLon) <= 3;
  }

  static double meters(double lat, double lon, double otherLat, double otherLon) {
    double p = Math.toRadians(lat),
        q = Math.toRadians(otherLat),
        d = Math.toRadians(otherLat - lat),
        l = Math.toRadians(otherLon - lon);
    double h =
        Math.sin(d / 2) * Math.sin(d / 2)
            + Math.cos(p) * Math.cos(q) * Math.sin(l / 2) * Math.sin(l / 2);
    return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
  }
}
