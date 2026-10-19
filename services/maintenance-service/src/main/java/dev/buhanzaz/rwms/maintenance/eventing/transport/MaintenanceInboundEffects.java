package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Domain effects run in the same PostgreSQL transaction as inbox and checkpoint updates. */
@FunctionalInterface
public interface MaintenanceInboundEffects {
  void apply(InboundEvent event, TaskCorrelation correlation);

  record InboundEvent(
      String sourceTopic,
      UUID eventId,
      String eventType,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      JsonNode payload) {}

  record TaskCorrelation(
      UUID externalTaskId,
      UUID boardTaskId,
      UUID queueEntryId,
      UUID boardTaskEventId,
      UUID queueEntryEventId) {
    public static TaskCorrelation none() {
      return new TaskCorrelation(null, null, null, null, null);
    }

    public boolean complete() {
      return externalTaskId != null && boardTaskId != null && queueEntryId != null;
    }
  }
}
