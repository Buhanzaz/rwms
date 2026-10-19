package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;

/** Read projection passed into MapStruct for a safe event payload. */
public record LogisticsEventSource(LogisticsDocument document, int lineCount, String resultCode) {}
