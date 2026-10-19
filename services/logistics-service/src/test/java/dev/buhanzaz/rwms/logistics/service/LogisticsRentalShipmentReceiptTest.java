package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsIdempotencyRecord;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsIdempotencyRecordRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Proves preflight reads the existing exact receipt without locks, writes or response projection. */
class LogisticsRentalShipmentReceiptTest {
  private static final UUID SUBJECT = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();
  private static final String OPERATION = "CREATE_RENTAL_ORDER_SHIPMENT";
  private static final String CHECKSUM = "a".repeat(64);
  private final LogisticsIdempotencyRecordRepository receipts =
      mock(LogisticsIdempotencyRecordRepository.class);
  private final LogisticsTransactionLock lock = mock(LogisticsTransactionLock.class);
  private final LogisticsDocumentReadProjection projection =
      mock(LogisticsDocumentReadProjection.class);
  private final DocumentDriverTaskPlanner driverTasks = mock(DocumentDriverTaskPlanner.class);
  private final LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
  private final LogisticsRentalOrderShipmentCoordinator coordinator =
      new LogisticsRentalOrderShipmentCoordinator(
          documents,
          mock(LogisticsDocumentLineRepository.class),
          mock(RentalOrderUnitTermRepository.class),
          mock(ShipmentFurnitureMovementTaskRepository.class),
          mock(ShipmentFurnitureTaskService.class),
          mock(LogisticsEventStore.class),
          mock(LogisticsDocumentWarehouseAdmission.class),
          new LogisticsDocumentIdempotency(
              receipts, mock(LogisticsIdempotencyProperties.class), lock, mock(ObjectMapper.class)),
          projection,
          driverTasks,
          mock(ShipmentTaskSettingsService.class),
          mock(CustomerRentalSessionRepository.class),
          mock(LogisticsDocumentAttemptWriter.class));

  @Test
  void exactReceiptExposesOnlyItsOwningOrderWithoutAcquiringAReplayLock() {
    LogisticsDocument document = receipt();
    when(document.getRentalOrderId()).thenReturn(ORDER);

    assertThat(coordinator.rentalOrderShipmentReceiptOrderId(SUBJECT, KEY, CHECKSUM))
        .isEqualTo(ORDER);

    verify(receipts).findBySubjectIdAndOperationNameAndIdempotencyKey(SUBJECT, OPERATION, KEY);
    verifyNoMoreInteractions(receipts);
    verifyNoInteractions(lock, projection, driverTasks, documents);
  }

  @Test
  void missingReceiptReturnsNoOwnerAndChangedChecksumFailsClosed() {
    assertThat(coordinator.rentalOrderShipmentReceiptOrderId(SUBJECT, KEY, CHECKSUM)).isNull();
    receipt();
    assertThatThrownBy(
            () -> coordinator.rentalOrderShipmentReceiptOrderId(SUBJECT, KEY, "b".repeat(64)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("different command");
    verifyNoInteractions(lock, projection, driverTasks, documents);
  }

  @Test
  void malformedCommittedReceiptCannotMasqueradeAsANewCommand() {
    receipt();
    assertThatThrownBy(() -> coordinator.rentalOrderShipmentReceiptOrderId(SUBJECT, KEY, CHECKSUM))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("no owning rental order");
    verifyNoInteractions(lock, projection, driverTasks, documents);
  }

  private LogisticsDocument receipt() {
    LogisticsDocument document = mock(LogisticsDocument.class);
    OffsetDateTime createdAt = OffsetDateTime.now();
    LogisticsIdempotencyRecord record =
        LogisticsIdempotencyRecord.create(
            SUBJECT, OPERATION, KEY, CHECKSUM, document, createdAt, createdAt.plusDays(1));
    when(receipts.findBySubjectIdAndOperationNameAndIdempotencyKey(SUBJECT, OPERATION, KEY))
        .thenReturn(Optional.of(record));
    return document;
  }
}
