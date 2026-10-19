package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import java.util.UUID;

/** Sanitized event fact; PII and downstream payloads stay in local projections. */
public record LogisticsEventPayload(
    UUID documentId,
    LogisticsDocumentType documentType,
    LogisticsDocumentState state,
    UUID warehouseId,
    UUID destinationWarehouseId,
    int lineCount,
    String resultCode) {}
