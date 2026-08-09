package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies stable search-step key derivation from one durable tool-call root. */
class AssistantSearchIdempotencyKeysTest {
  @Test
  void derivesStableDistinctKeysForDirectProbeAndFinalPayloadSteps() {
    UUID root = UUID.fromString("7c798445-d109-44a1-b977-f4bba2f8a10b");

    assertThat(AssistantSearchIdempotencyKeys.direct(root)).isEqualTo(root);
    assertThat(AssistantSearchIdempotencyKeys.probe(root, 0))
        .isEqualTo(AssistantSearchIdempotencyKeys.probe(root, 0))
        .isNotEqualTo(AssistantSearchIdempotencyKeys.probe(root, 1))
        .isNotEqualTo(AssistantSearchIdempotencyKeys.finalSelection(root))
        .isNotEqualTo(root);
    assertThat(AssistantSearchIdempotencyKeys.finalSelection(root))
        .isEqualTo(AssistantSearchIdempotencyKeys.finalSelection(root));
    assertThat(AssistantSearchIdempotencyKeys.probe(UUID.randomUUID(), 0))
        .isNotEqualTo(AssistantSearchIdempotencyKeys.probe(root, 0));
  }
}
