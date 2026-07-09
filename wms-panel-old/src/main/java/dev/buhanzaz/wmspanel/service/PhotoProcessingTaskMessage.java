package dev.buhanzaz.wmspanel.service;

import java.util.UUID;

public record PhotoProcessingTaskMessage(
        UUID photoId,
        String bucket,
        String incomingObjectKey,
        String familyRootKey,
        String contentType,
        UUID rentalItemId,
        UUID eventId,
        String warehouseCode,
        String itemNumber,
        String eventType,
        Integer previewLongEdge,
        Integer thumbLongEdge,
        Integer tinyLongEdge
) {
}
