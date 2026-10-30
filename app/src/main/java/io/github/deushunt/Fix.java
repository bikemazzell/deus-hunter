package io.github.deushunt;

final class Fix {
  final double latitude, longitude, accuracy;
  final long monotonicNanos;
  final Double altitude, speed;

  Fix(
      double latitude,
      double longitude,
      double accuracy,
      long monotonicNanos,
      Double altitude,
      Double speed) {
    this.latitude = latitude;
    this.longitude = longitude;
    this.accuracy = accuracy;
    this.monotonicNanos = monotonicNanos;
    this.altitude = altitude != null && Double.isFinite(altitude) ? altitude : null;
    this.speed = speed != null && Double.isFinite(speed) && speed >= 0 ? speed : null;
  }

  boolean valid() {
    return Double.isFinite(latitude)
        && Math.abs(latitude) <= 90
        && Double.isFinite(longitude)
        && Math.abs(longitude) <= 180
        && Double.isFinite(accuracy)
        && accuracy >= 0
        && monotonicNanos >= 0;
  }
}
