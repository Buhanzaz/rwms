package dev.buhanzaz.rwms.logistics.vehicle.domain;

/** Lifecycle state of one logistics-owned vehicle reservation and placement history row. */
public enum VehicleOperationalAssignmentStatus {
  PLANNED,
  IN_TRANSIT,
  ACTIVE,
  COMPLETED,
  CANCELLED
}
