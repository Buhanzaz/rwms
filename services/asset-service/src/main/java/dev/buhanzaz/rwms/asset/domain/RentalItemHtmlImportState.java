package dev.buhanzaz.rwms.asset.domain;

/**
 * Enumerates permitted rental item html import state values in the asset persistent workflow state.
 */
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
