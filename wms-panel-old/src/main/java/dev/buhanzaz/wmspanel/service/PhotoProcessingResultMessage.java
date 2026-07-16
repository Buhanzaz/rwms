package dev.buhanzaz.wmspanel.service;

import java.util.UUID;

public record PhotoProcessingResultMessage(
        UUID photoId,
        String status,
        String originalObjectKey,
        Integer originalWidth,
        Integer originalHeight,
        Integer previewWidth,
        Integer previewHeight,
        Integer thumbWidth,
        Integer thumbHeight,
        Integer tinyWidth,
        Integer tinyHeight,
        String error
) {
}
