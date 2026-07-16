package dev.buhanzaz.rwms.platform.contracts;

import java.util.UUID;

/** Correlation metadata shared by synchronous requests and integration events. */
public record CorrelationContext(UUID correlationId, UUID causationId) {

    public CorrelationContext {
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }
    }
}
