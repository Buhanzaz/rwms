package dev.buhanzaz.rwms.logistics.inquiry.domain;

/** Durable state of one idempotent inquiry selection command. */
public enum RentalInquirySelectionReceiptState {
  PREPARED,
  COMPLETED,
  REJECTED,
  EXPIRED
}
