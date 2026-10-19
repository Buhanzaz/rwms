package dev.buhanzaz.rwms.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RwmsKafkaEventMetadataTest {

    private static final String OWNER_PROOF_DESTINATION = "rwms.task-board.entry-owner-proof.v1";

    @Test
    void acceptsTheFrozenTaskBoardOwnerProofContract() {
        RwmsKafkaEventMetadata metadata = ownerProofMetadata(
                "task-board-service", "task-board.entry-owner-proof.changed.v1", "TASK_BOARD_ENTRY_OWNER_PROOF");

        assertThat(metadata.aggregateFamilyDestination()).isEqualTo(OWNER_PROOF_DESTINATION);
        assertThat(metadata.matchesDestination(OWNER_PROOF_DESTINATION)).isTrue();
    }

    @Test
    void rejectsNearMissesOfTheFrozenTaskBoardOwnerProofContract() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ownerProofMetadata(
                        "another-service", "task-board.entry-owner-proof.changed.v1", "TASK_BOARD_ENTRY_OWNER_PROOF"))
                .withMessageContaining("frozen producer/eventType/aggregateType contract");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ownerProofMetadata(
                        "task-board-service", "task-board.entry-owner-proof.changed.v1", "ENTRY_OWNER_PROOF"))
                .withMessageContaining("frozen producer/eventType/aggregateType contract");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ownerProofMetadata(
                        "task-board-service", "task-board.task-board-entry-owner-proof.changed.v1", "TASK_BOARD_ENTRY_OWNER_PROOF"))
                .withMessageContaining("frozen producer/eventType/aggregateType contract");
    }

    private static RwmsKafkaEventMetadata ownerProofMetadata(
            String producer, String eventType, String aggregateType) {
        return new RwmsKafkaEventMetadata(
                2,
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                eventType,
                1,
                null,
                aggregateType,
                "50000000-0000-0000-0000-000000000005",
                1,
                producer,
                Instant.parse("2026-07-26T09:00:00Z"),
                UUID.fromString("60000000-0000-0000-0000-000000000006"),
                null);
    }
}
