package dev.buhanzaz.rwms.taskboard.domain;

/** Durable upload/finalization state of a Driver Shift photo reservation. */
public enum ShiftPhotoState {
  RESERVED,
  PROCESSING,
  READY,
  REVIEW_REQUIRED
}
