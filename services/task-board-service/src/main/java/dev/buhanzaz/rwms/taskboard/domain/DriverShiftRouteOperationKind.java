package dev.buhanzaz.rwms.taskboard.domain;

/** Stable executable operation kinds accepted from the logistics-owned route plan. */
public enum DriverShiftRouteOperationKind {
  ORIGIN_START,
  TRANSFER_LOAD,
  INBOUND_POSITIONING,
  TRANSFER_UNLOAD,
  DEPOT_LOAD,
  DELIVERY,
  PICKUP,
  DEPOT_UNLOAD,
  DEPOT_RETURN,
  RETURN_POSITIONING
}
