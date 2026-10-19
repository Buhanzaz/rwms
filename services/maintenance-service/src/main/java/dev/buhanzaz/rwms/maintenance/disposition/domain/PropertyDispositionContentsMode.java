package dev.buhanzaz.rwms.maintenance.disposition.domain;

/** How the frozen cabin-contents snapshot is handled after approval. */
public enum PropertyDispositionContentsMode {
  MOVE_SELECTED_TO_STOCK,
  DISPOSE_WITH_CABIN
}
