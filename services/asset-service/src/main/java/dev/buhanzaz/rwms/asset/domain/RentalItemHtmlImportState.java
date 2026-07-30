package dev.buhanzaz.rwms.asset.domain;

public enum RentalItemHtmlImportState {
  DRAFT,
  REVIEW_REQUIRED,
  READY,
  COMMITTING,
  ASSETS_COMMITTED,
  MEDIA_IMPORTING,
  COMPLETED,
  COMPLETED_WITH_WARNINGS,
  FAILED
}
