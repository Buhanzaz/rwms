package dev.buhanzaz.rwms.logistics.vehicle.domain;

/** Distinguishes a route-only vehicle reservation from post-arrival warehouse placement. */
public enum VehicleOperationalAssignmentMode {
  TRIP_ONLY,
  TEMPORARY,
  PERMANENT
}
