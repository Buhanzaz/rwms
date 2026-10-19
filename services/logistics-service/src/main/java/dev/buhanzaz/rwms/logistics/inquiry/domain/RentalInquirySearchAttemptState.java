package dev.buhanzaz.rwms.logistics.inquiry.domain;

/** Durable lifecycle of one subject-scoped rental-inquiry cabin search receipt. */
public enum RentalInquirySearchAttemptState {
  /** The exact downstream command is frozen and may be safely resumed. */
  PREPARED,
  /** The successful response is frozen and can be replayed without a remote call. */
  COMPLETED,
  /** A classified domain-semantic rejection ended the attempt. */
  REJECTED,
  /** The frozen hold lifetime elapsed, so any matching remote holds are necessarily dead. */
  EXPIRED
}
