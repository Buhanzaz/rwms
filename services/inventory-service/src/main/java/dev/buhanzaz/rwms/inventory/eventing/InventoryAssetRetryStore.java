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

/**
 * Persistence boundary for retrying inventory inbox records without losing their identity.
 */
@Repository
public class InventoryAssetRetryStore {
  private final InventoryAssetInboxStore inbox;
  private final InventoryPostgresJsonbCanonicalizer jsonb;
  private final ObjectMapper mapper;
  private final InventoryDeadLetterStore deadLetters;

  public InventoryAssetRetryStore(
      InventoryAssetInboxStore inbox,
      InventoryPostgresJsonbCanonicalizer jsonb,
      ObjectMapper mapper,
      InventoryDeadLetterStore deadLetters) {
    this.inbox = inbox;
    this.jsonb = jsonb;
    this.mapper = mapper;
    this.deadLetters = deadLetters;
  }

  /**
   * Stores the first retry attempt in an independent transaction after a consumer failure.
   *
   * <p>The lightweight identity/key check prevents a malformed fallback from taking ownership of
   * another asset; full envelope validation remains with {@link InventoryAssetInboxProcessor}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean scheduleInitial(byte[] bytes, byte[] recordKey) {
    try {
      JsonNode root = mapper.readTree(bytes);
      UUID eventId = UUID.fromString(root.path("eventId").asText());
      UUID assetId = UUID.fromString(root.path("aggregateId").asText());
      long version = root.path("aggregateVersion").asLong(-1);
      String key = new String(recordKey, StandardCharsets.UTF_8);
      if (version < 0 || !assetId.toString().equals(key)) return false;
      String raw = new String(bytes, StandardCharsets.UTF_8);
      String body = jsonb.canonicalize(raw);
      if (body == null) return false;
      inbox.insertInitialRetry(
          eventId,
          assetId,
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

  /**
   * Returns one retry whose durable backoff has elapsed without reserving it outside the worker's
   * transaction.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> due() {
    return inbox.dueRetry();
  }

  /**
   * Atomically advances a failed retry's exponential backoff or records its terminal DLT state.
   *
   * <p>The inbox row lock, terminal state and sanitized DLT write stay in this independent
   * transaction so concurrent recovery workers cannot create an extra terminal attempt.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(UUID eventId) {
    InventoryAssetInboxStore.RetryState state = inbox.lockRetryState(eventId);
    if (state == null) return;
    if (state.attempts() >= 3) {
      inbox.markProcessingFailedTerminal(eventId);
      deadLetters.record(
          "PROCESSING_FAILED",
          state.hash(),
          InventoryAssetInboxProcessor.TOPIC,
          eventId);
      return;
    }
    int attempts = state.attempts() + 1;
    long delaySeconds = 1L << (attempts - 1);
    inbox.reschedule(eventId, attempts, delaySeconds);
  }
}
