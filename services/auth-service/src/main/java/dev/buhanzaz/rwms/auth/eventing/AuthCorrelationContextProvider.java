package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Supplies correlation metadata for authoritative auth facts.
 *
 * <p>A valid correlation ID propagated by the web layer is preserved. Scheduled and other
 * background work receives a new correlation ID, keeping persisted events traceable without
 * trusting malformed MDC content.
 */
@Component
public class AuthCorrelationContextProvider {

    /**
     * Returns the current validated correlation context or creates one for background work.
     *
     * @return a context whose correlation ID is always a UUID
     */
    public CorrelationContext current() {
        String candidate = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (candidate != null) {
            try {
                return new CorrelationContext(UUID.fromString(candidate), null);
            } catch (IllegalArgumentException ignored) {
                // The web filter normally guarantees a UUID. Background work gets a fresh context.
            }
        }
        return new CorrelationContext(UUID.randomUUID(), null);
    }
}
