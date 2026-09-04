package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;
import java.util.List;

/**
 * Bounded logistics-document read page whose content remains compatible with the historical JSON
 * array response while pagination metadata is exposed by the HTTP boundary.
 */
public record LogisticsDocumentPage(
    List<LogisticsDocumentView> content,
    int page,
    int pageSize,
    long totalElements,
    int totalPages,
    boolean hasNext) {
  public LogisticsDocumentPage {
    content = List.copyOf(content);
    if (page < 0
        || pageSize < 1
        || totalElements < 0
        || totalPages < 0
        || (hasNext && page + 1 >= totalPages)) {
      throw new IllegalArgumentException("Logistics document page metadata is invalid");
    }
  }
}
