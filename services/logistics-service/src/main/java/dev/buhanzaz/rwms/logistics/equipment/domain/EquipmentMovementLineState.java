package dev.buhanzaz.rwms.logistics.equipment.domain;

/** State of one source reservation within an equipment movement task. */
public enum EquipmentMovementLineState {
  PENDING_RESERVATION,
  RESERVED,
  EXECUTED,
  RELEASED
}
