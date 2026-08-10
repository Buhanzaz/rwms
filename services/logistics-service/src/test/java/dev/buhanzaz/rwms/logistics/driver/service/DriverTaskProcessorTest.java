package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies idempotency-key derivation for the durable driver completion relay. */
class DriverTaskProcessorTest {
  private final DriverTaskWorkflowStore store = mock(DriverTaskWorkflowStore.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final DriverTaskProcessor processor = new DriverTaskProcessor(store, dependencies);

  @Test
  void groupedShipmentUsesOneDistinctMediaCommandKeyPerCabin() {
    UUID taskId = UUID.randomUUID();
    UUID firstCabinId = UUID.randomUUID();
    UUID secondCabinId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    DriverTaskWorkflowStore.CoverWork first =
        new DriverTaskWorkflowStore.CoverWork(taskId, firstCabinId, entryId, mediaId, true);
    DriverTaskWorkflowStore.CoverWork second =
        new DriverTaskWorkflowStore.CoverWork(taskId, secondCabinId, entryId, mediaId, true);
    when(store.nextWork(taskId))
        .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
    when(dependencies.setCabinCoverFromTaskEvidence(any(), any(), any(), any()))
        .thenReturn(
            coverChange(firstCabinId, entryId, mediaId), coverChange(secondCabinId, entryId, mediaId));

    assertThat(processor.processUntilIdle(taskId)).isEqualTo(2);

    ArgumentCaptor<UUID> commandKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> cabinIds = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .setCabinCoverFromTaskEvidence(commandKeys.capture(), cabinIds.capture(), any(), any());
    assertThat(cabinIds.getAllValues()).containsExactly(firstCabinId, secondCabinId);
    assertThat(commandKeys.getAllValues())
        .containsExactly(
            derivedCoverKey(taskId, firstCabinId), derivedCoverKey(taskId, secondCabinId))
        .doesNotHaveDuplicates();
  }

  @Test
  void legacyOneCabinCoverRetainsItsOriginalIdempotencyKey() {
    UUID taskId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    when(store.nextWork(taskId))
        .thenReturn(
            Optional.of(
                new DriverTaskWorkflowStore.CoverWork(
                    taskId, cabinId, entryId, mediaId, false)),
            Optional.empty());
    when(dependencies.setCabinCoverFromTaskEvidence(any(), any(), any(), any()))
        .thenReturn(coverChange(cabinId, entryId, mediaId));

    assertThat(processor.processUntilIdle(taskId)).isEqualTo(1);

    ArgumentCaptor<UUID> commandKey = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies)
        .setCabinCoverFromTaskEvidence(commandKey.capture(), any(), any(), any());
    assertThat(commandKey.getValue())
        .isEqualTo(
            UUID.nameUUIDFromBytes(
                ("driver-task:cover:" + taskId).getBytes(StandardCharsets.UTF_8)));
  }

  private static LogisticsDependencyGateway.CabinCoverChange coverChange(
      UUID cabinId, UUID entryId, UUID mediaId) {
    return new LogisticsDependencyGateway.CabinCoverChange(
        cabinId,
        UUID.randomUUID(),
        mediaId,
        1,
        entryId,
        0,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static UUID derivedCoverKey(UUID taskId, UUID cabinId) {
    return UUID.nameUUIDFromBytes(
        ("driver-task:cover:" + cabinId + ":" + taskId).getBytes(StandardCharsets.UTF_8));
  }
}
