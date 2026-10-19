package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ArriveTransferLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferArrivalPreflightView;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies retry-stable driver execution commands against the existing transfer aggregate API. */
class DriverTransferExecutionServiceTest {
  private final LogisticsDocumentService documents = mock(LogisticsDocumentService.class);
  private final DriverTransferExecutionService service =
      new DriverTransferExecutionService(documents);

  @Test
  void repeatedLineDepartureUsesTheSameIdempotencyAndCorrelationKeys() {
    DriverTaskWorkflowStore.TransferDepartureWork work =
        new DriverTaskWorkflowStore.TransferDepartureWork(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            4L);

    service.depart(work);
    service.depart(work);

    ArgumentCaptor<UUID> idempotencyKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> correlationIds = ArgumentCaptor.forClass(UUID.class);
    verify(documents, times(2))
        .departTransferLine(
            eq(work.actorId()),
            idempotencyKeys.capture(),
            correlationIds.capture(),
            eq(work.documentId()),
            eq(work.lineId()),
            eq(work.expectedDocumentVersion()),
            eq(work.expectedLineVersion()));
    assertThat(idempotencyKeys.getAllValues()).hasSize(2).doesNotContainNull();
    assertThat(idempotencyKeys.getAllValues().getFirst())
        .isEqualTo(idempotencyKeys.getAllValues().get(1));
    assertThat(correlationIds.getAllValues().getFirst())
        .isEqualTo(correlationIds.getAllValues().get(1));
  }

  @Test
  void arrivalReusesSelectedEvidenceAndAddsRepairPriorityOnlyWhenRequired() {
    UUID activeRepairId = UUID.randomUUID();
    DriverTaskWorkflowStore.TransferArrivalWork work =
        new DriverTaskWorkflowStore.TransferArrivalWork(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            5,
            8L,
            UUID.randomUUID(),
            3,
            4);
    when(documents.transferArrivalPreflight(
            work.documentId(),
            work.lineId(),
            work.expectedDocumentVersion(),
            work.expectedLineVersion()))
        .thenReturn(
            new TransferArrivalPreflightView(
                work.documentId(), work.lineId(), activeRepairId, true, List.of()));

    service.arrive(work);

    ArgumentCaptor<ArriveTransferLineRequest> request =
        ArgumentCaptor.forClass(ArriveTransferLineRequest.class);
    verify(documents)
        .arriveTransferLine(
            eq(work.actorId()),
            any(),
            any(),
            eq(work.documentId()),
            eq(work.lineId()),
            eq(work.expectedDocumentVersion()),
            eq(work.expectedLineVersion()),
            request.capture());
    assertThat(request.getValue().priority()).isEqualTo(4);
    assertThat(request.getValue().references())
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.mediaId()).isEqualTo(work.mediaId());
              assertThat(reference.generation()).isEqualTo(work.mediaGeneration());
            });
  }
}
