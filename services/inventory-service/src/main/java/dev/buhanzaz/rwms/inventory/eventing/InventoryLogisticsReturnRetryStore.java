package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.persistence.InventoryPostgresJsonbCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Persists bounded application-managed retries for logistics normal-return facts. */
@Repository
public class InventoryLogisticsReturnRetryStore {
  private final InventoryLogisticsReturnInboxStore inbox;
  private final InventoryPostgresJsonbCanonicalizer jsonb;
  private final ObjectMapper mapper;
  private final InventoryDeadLetterStore deadLetters;

  public InventoryLogisticsReturnRetryStore(
      InventoryLogisticsReturnInboxStore inbox,
      InventoryPostgresJsonbCanonicalizer jsonb,
      ObjectMapper mapper,
      InventoryDeadLetterStore deadLetters) {
    this.inbox = inbox;
    this.jsonb = jsonb;
    this.mapper = mapper;
    this.deadLetters = deadLetters;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean scheduleInitial(
      InventoryLogisticsReturnInboxStore.Source source, byte[] bytes, byte[] recordKey) {
    try {
      JsonNode root = mapper.readTree(bytes);
      UUID eventId = UUID.fromString(root.path("eventId").asText());
      UUID returnId = UUID.fromString(root.path("aggregateId").asText());
      long version = root.path("aggregateVersion").asLong(-1);
      String key = new String(recordKey, StandardCharsets.UTF_8);
      if (version < 0 || !returnId.toString().equals(key)) return false;
      String body = jsonb.canonicalize(new String(bytes, StandardCharsets.UTF_8));
      if (body == null) return false;
      inbox.insertInitialRetry(
          source,
          eventId,
          returnId,
          key,
          version,
          root.path("eventType").asText(),
          InventoryEventChecksum.sha256(bytes),
          body);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  @Transactional(readOnly = true)
  public Optional<UUID> due() {
    return inbox.dueRetry();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(UUID eventId) {
    InventoryLogisticsReturnInboxStore.RetryState state = inbox.lockRetryState(eventId);
    if (state == null) return;
    if (state.attempts() >= 3) {
      inbox.markProcessingFailedTerminal(eventId);
      deadLetters.record(
          "PROCESSING_FAILED",
          state.hash(),
          state.sourceTopic(),
          eventId);
      return;
    }
    int attempts = state.attempts() + 1;
    inbox.reschedule(eventId, attempts, 1L << (attempts - 1));
  }
}
