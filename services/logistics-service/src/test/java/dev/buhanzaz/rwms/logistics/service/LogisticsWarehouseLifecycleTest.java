package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionEvidence;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionKind;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle.AdmissionTicket;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Verifies that a caller cannot turn an explicit test-only ticket into a runtime bypass. */
class LogisticsWarehouseLifecycleTest {

  @Test
  void publicCreateBypassTicketIsRejectedOutsideTheTestProfile() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    LogisticsWarehouseLifecycleStore store = mock(LogisticsWarehouseLifecycleStore.class);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("production");
    LogisticsWarehouseLifecycle lifecycle =
        new LogisticsWarehouseLifecycle(dependencies, store, environment);
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    AdmissionTicket bypass =
        new AdmissionTicket(
            UUID.randomUUID(),
            List.of(
                new AdmissionRequirement(
                    warehouseId, WarehouseOperationDirection.INCOMING)),
            occurredAt,
            Map.of(warehouseId, occurredAt.toLocalDate()),
            true,
            AdmissionKind.TEST_ONLY);

    assertThat(bypass.admissionEvidence()).isEmpty();
    assertThat(bypass.evidenceFor(warehouseId)).isEmpty();
    assertThatThrownBy(() -> lifecycle.consume(bypass))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("not ready");
    verifyNoInteractions(dependencies, store);
  }

  @Test
  void publicTicketConstructorCannotForgeAParentOwnedBypass() {
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

    assertThatThrownBy(
            () ->
                new AdmissionTicket(
                    UUID.randomUUID(),
                    List.of(
                        new AdmissionRequirement(
                            warehouseId, WarehouseOperationDirection.INCOMING)),
                    occurredAt,
                    Map.of(warehouseId, occurredAt.toLocalDate()),
                    true,
                    AdmissionKind.OWNED_CONTINUATION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TEST_ONLY");
  }

  @Test
  void remoteTicketRejectsMissingExtraAndDirectionMismatchedEvidence() {
    UUID warehouseId = UUID.randomUUID();
    UUID otherWarehouseId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    List<AdmissionRequirement> requirements =
        List.of(
            new AdmissionRequirement(
                warehouseId, WarehouseOperationDirection.INCOMING));
    Map<UUID, java.time.LocalDate> localDates =
        Map.of(warehouseId, occurredAt.toLocalDate());

    assertThatThrownBy(
            () ->
                AdmissionTicket.remote(
                    UUID.randomUUID(), requirements, occurredAt, localDates, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
    assertThatThrownBy(
            () ->
                AdmissionTicket.remote(
                    UUID.randomUUID(),
                    requirements,
                    occurredAt,
                    localDates,
                    List.of(
                        new AdmissionEvidence(
                            warehouseId, WarehouseOperationDirection.INCOMING, 4),
                        new AdmissionEvidence(
                            otherWarehouseId, WarehouseOperationDirection.OUTGOING, 7))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incomplete");
    assertThatThrownBy(
            () ->
                AdmissionTicket.remote(
                    UUID.randomUUID(),
                    requirements,
                    occurredAt,
                    localDates,
                    List.of(
                        new AdmissionEvidence(
                            warehouseId, WarehouseOperationDirection.OUTGOING, 4))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void evidencedReplayCandidateCannotReachANewCreateConsumption() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    LogisticsWarehouseLifecycleStore store = mock(LogisticsWarehouseLifecycleStore.class);
    LogisticsWarehouseLifecycle lifecycle =
        new LogisticsWarehouseLifecycle(dependencies, store, new MockEnvironment());
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    AdmissionTicket replay =
        AdmissionTicket.evidencedReplayCandidate(
            UUID.randomUUID(),
            occurredAt,
            List.of(
                new AdmissionEvidence(
                    warehouseId, WarehouseOperationDirection.OUTGOING, 9)));

    assertThatThrownBy(() -> lifecycle.consume(replay))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("cannot create");
    verifyNoInteractions(dependencies, store);
  }
}
