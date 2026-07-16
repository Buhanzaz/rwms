package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TaskBoardCorrelationContextProvider {
  public CorrelationContext current() {
    String candidate = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (candidate != null) {
      try {
        return new CorrelationContext(UUID.fromString(candidate), null);
      } catch (IllegalArgumentException ignored) {
        // Background operations get a fresh correlation context.
      }
    }
    return new CorrelationContext(UUID.randomUUID(), null);
  }
}
