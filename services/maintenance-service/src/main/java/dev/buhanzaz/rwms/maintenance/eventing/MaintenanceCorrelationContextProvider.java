package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Creates maintenance event correlation from the request MDC, with a fresh correlation ID when no
 * valid request context is available.
 */
@Component
public class MaintenanceCorrelationContextProvider {
  public CorrelationContext current() {
    try {
      return new CorrelationContext(UUID.fromString(MDC.get(CorrelationIdFilter.MDC_KEY)), null);
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return new CorrelationContext(UUID.randomUUID(), null);
    }
  }
}
