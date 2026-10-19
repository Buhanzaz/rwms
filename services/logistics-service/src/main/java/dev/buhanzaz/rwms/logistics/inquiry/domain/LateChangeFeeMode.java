package dev.buhanzaz.rwms.logistics.inquiry.domain;

/** Unit of the configured late-change fee; absence of a mode means no policy is configured. */
public enum LateChangeFeeMode {
  FIXED,
  PERCENT
}
