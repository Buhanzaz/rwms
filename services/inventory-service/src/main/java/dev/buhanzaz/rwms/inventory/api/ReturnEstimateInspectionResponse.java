package dev.buhanzaz.rwms.inventory.api;

import java.util.UUID;

/** Read-only status of inventory inspection evidence for one maintenance return estimate. */
public record ReturnEstimateInspectionResponse(
    State state, UUID inventoryId, UUID findingId, String cabinNumber) {
  public enum State {
    CONFIRMED,
    PENDING,
    NOT_REQUIRED
  }
}
