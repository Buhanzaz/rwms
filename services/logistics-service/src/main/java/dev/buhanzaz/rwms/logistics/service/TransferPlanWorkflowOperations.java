package dev.buhanzaz.rwms.logistics.service;

/** Stable operation names used by the fenced transfer-plan relay. */
final class TransferPlanWorkflowOperations {
  static final String UNIT_RESERVE = "XFER_PLAN_UNITS_RESERVE";
  static final String DRIVER_TASK_PLAN = "XFER_PLAN_DRIVER_TASK";
  static final String TRIP_DRIVER_ASSIGN = "XFER_PLAN_TRIP_DRIVER";
  static final String REPOSITION_DRIVER_ASSIGN = "XFER_PLAN_MOVE_DRIVER";
  static final String TRIP_DRIVER_TRANSIT = "XFER_PLAN_TRIP_TRANSIT";
  static final String REPOSITION_DRIVER_TRANSIT = "XFER_PLAN_MOVE_TRANSIT";
  static final String TRIP_DRIVER_COMPLETE = "XFER_PLAN_TRIP_COMPLETE";
  static final String REPOSITION_DRIVER_ACTIVE = "XFER_PLAN_MOVE_ACTIVE";
  static final String TRIP_DRIVER_CANCEL = "XFER_PLAN_TRIP_CANCEL";
  static final String REPOSITION_DRIVER_CANCEL = "XFER_PLAN_MOVE_CANCEL";
  static final String UNIT_RELEASE = "XFER_PLAN_UNITS_RELEASE";
  static final String FURNITURE_EXECUTE = "XFER_PLAN_FURN_EXECUTE";
  static final String FURNITURE_RESERVE_PREFIX = "XFER_PLAN_FURN_RES:";
  static final String FURNITURE_RELEASE_PREFIX = "XFER_PLAN_FURN_REL:";

  private TransferPlanWorkflowOperations() {}

  static String furnitureReserve(int position) {
    return FURNITURE_RESERVE_PREFIX + position;
  }

  static String furnitureRelease(int position) {
    return FURNITURE_RELEASE_PREFIX + position;
  }
}
