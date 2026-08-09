package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Supplies the current correlation ID for asset event metadata. It generates a fresh correlation
 * ID when request MDC data is absent or malformed and leaves causation unset.
 */
@Component
public class AssetCorrelationContextProvider {
  public CorrelationContext current() {
    try {
      return new CorrelationContext(UUID.fromString(MDC.get(CorrelationIdFilter.MDC_KEY)), null);
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return new CorrelationContext(UUID.randomUUID(), null);
    }
  }
}
