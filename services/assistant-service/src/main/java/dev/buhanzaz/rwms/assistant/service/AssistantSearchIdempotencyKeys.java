package dev.buhanzaz.rwms.assistant.service;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Derives stable, payload-step-specific cabin-search keys from one durable tool-call root. */
public final class AssistantSearchIdempotencyKeys {
  private static final String PREFIX = "rwms:assistant:cabin-search\u001f";

  private AssistantSearchIdempotencyKeys() {}

  /** Uses the durable tool-call identity itself when a search needs no allocation probes. */
  public static UUID direct(UUID toolCallId) {
    return Objects.requireNonNull(toolCallId, "toolCallId");
  }

  /** Derives one stable key for a logical allocation probe with its own request payload. */
  public static UUID probe(UUID toolCallId, int logicalGroupIndex) {
    if (logicalGroupIndex < 0) {
      throw new IllegalArgumentException("logicalGroupIndex must not be negative");
    }
    return derived(toolCallId, "probe:" + logicalGroupIndex);
  }

  /** Derives the stable key for the final bounded selection payload. */
  public static UUID finalSelection(UUID toolCallId) {
    return derived(toolCallId, "final");
  }

  private static UUID derived(UUID toolCallId, String step) {
    String value = PREFIX + Objects.requireNonNull(toolCallId, "toolCallId") + '\u001f' + step;
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
