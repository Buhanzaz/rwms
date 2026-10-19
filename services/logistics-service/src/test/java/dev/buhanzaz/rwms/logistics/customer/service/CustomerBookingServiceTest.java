package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.AcceptCustomerCabinRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerSignaturePoint;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerSignatureStroke;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinAcceptance;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.mapper.CustomerReceptionResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerCabinAcceptanceRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerCabinProblemRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTaskMember;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.json.JsonMapper;

/** Verifies that customer reception follows the exact grouped shipment task outcome. */
class CustomerBookingServiceTest {
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID BOOKING = UUID.fromString("00000000-0000-0000-0000-000000000202");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000203");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000204");
  private static final UUID CABIN = UUID.fromString("00000000-0000-0000-0000-000000000205");
  private static final UUID DOCUMENT = UUID.fromString("00000000-0000-0000-0000-000000000206");
  private static final UUID LINE = UUID.fromString("00000000-0000-0000-0000-000000000207");
  private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000208");

  @Test
  void acceptsCabinOnlyAfterItsExactGroupedShipmentMemberCompleted() {
    Fixture fixture = fixture(DriverTaskState.COMPLETED, LINE, CABIN);
    UUID key = UUID.fromString("00000000-0000-0000-0000-000000000209");

    var response = fixture.service().accept(identity(), BOOKING, CABIN, key, signature());

    assertThat(response.signaturePointCount()).isEqualTo(2);
    assertThat(fixture.saved().getBookingId()).isEqualTo(BOOKING);
    assertThat(fixture.saved().getCabinUnitId()).isEqualTo(CABIN);
    assertThat(fixture.saved().getShipmentDocumentId()).isEqualTo(DOCUMENT);
    assertThat(fixture.saved().getShipmentLineId()).isEqualTo(LINE);
    assertThat(fixture.saved().getDriverTaskId()).isEqualTo(TASK);
  }

  @Test
  void rejectsCabinWhileTaskIsIncompleteOrMembershipDoesNotMatch() {
    Fixture incomplete = fixture(DriverTaskState.CURRENT, LINE, CABIN);
    Fixture wrongMember = fixture(DriverTaskState.COMPLETED, UUID.randomUUID(), CABIN);

    assertNotArrived(incomplete);
    assertNotArrived(wrongMember);
  }

  private static void assertNotArrived(Fixture fixture) {
    assertThatThrownBy(
            () -> fixture.service().accept(identity(), BOOKING, CABIN, UUID.randomUUID(), signature()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_CABIN_NOT_ARRIVED"));
  }

  private static Fixture fixture(
      DriverTaskState taskState, UUID memberLineId, UUID memberCabinId) {
    CustomerRentalSessionRepository sessions = mock(CustomerRentalSessionRepository.class);
    CustomerDeliverySlotRepository slots = mock(CustomerDeliverySlotRepository.class);
    CustomerCabinAcceptanceRepository acceptances =
        mock(CustomerCabinAcceptanceRepository.class);
    CustomerCabinProblemRepository problems = mock(CustomerCabinProblemRepository.class);
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository lines = mock(LogisticsDocumentLineRepository.class);
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
    RentalOrderService rentalOrders = mock(RentalOrderService.class);
    CustomerAuthorizer access = mock(CustomerAuthorizer.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    LogisticsTransactionLock transactionLock = mock(LogisticsTransactionLock.class);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    LogisticsDocument document = mock(LogisticsDocument.class);
    LogisticsDocumentLine line = mock(LogisticsDocumentLine.class);
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    DriverLogisticsTaskMember member = mock(DriverLogisticsTaskMember.class);
    CustomerCabinAcceptance[] saved = new CustomerCabinAcceptance[1];

    when(
            sessions.findByBookingIdAndCustomerSubjectId(BOOKING, SUBJECT))
        .thenReturn(Optional.of(session));
    when(session.getOrderId()).thenReturn(ORDER);
    when(session.getWarehouseId()).thenReturn(WAREHOUSE);
    when(documents.findAllByDocumentTypeAndRentalOrderIdOrderByCreatedAtAscIdAsc(
            LogisticsDocumentType.SHIPMENT, ORDER))
        .thenReturn(List.of(document));
    when(document.getId()).thenReturn(DOCUMENT);
    when(document.getState()).thenReturn(LogisticsDocumentState.IN_TRANSIT);
    when(lines.findAllByDocumentIdIn(Set.of(DOCUMENT))).thenReturn(List.of(line));
    when(line.getDocument()).thenReturn(document);
    when(line.getId()).thenReturn(LINE);
    when(line.getAssetId()).thenReturn(CABIN);
    when(driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, Set.of(DOCUMENT)))
        .thenReturn(List.of(task));
    when(task.getId()).thenReturn(TASK);
    when(task.getSourceId()).thenReturn(DOCUMENT);
    when(task.getKind()).thenReturn(DriverTaskKind.SHIPMENT);
    when(task.getState()).thenReturn(taskState);
    when(task.getMembers()).thenReturn(List.of(member));
    when(member.getDocumentLineId()).thenReturn(memberLineId);
    when(member.getCabinId()).thenReturn(memberCabinId);
    when(acceptances.findByCustomerSubjectIdAndIdempotencyKey(any(), any()))
        .thenReturn(Optional.empty());
    when(
            acceptances.findByBookingIdAndCabinUnitId(BOOKING, CABIN))
        .thenReturn(Optional.empty());
    when(acceptances.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              saved[0] = invocation.getArgument(0);
              return saved[0];
            });
    CustomerBookingService service =
        new CustomerBookingService(
            sessions,
            slots,
            acceptances,
            problems,
            documents,
            lines,
            driverTasks,
            rentalOrders,
            access,
            dependencies,
            Mappers.getMapper(CustomerReceptionResponseMapper.class),
            transactionLock,
            JsonMapper.builder().findAndAddModules().build(),
            Clock.fixed(Instant.parse("2026-08-27T09:00:00Z"), ZoneOffset.UTC));
    return new Fixture(service, saved);
  }

  private static CustomerIdentity identity() {
    return new CustomerIdentity(SUBJECT, "customer");
  }

  private static AcceptCustomerCabinRequest signature() {
    return new AcceptCustomerCabinRequest(
        List.of(
            new CustomerSignatureStroke(
                List.of(
                    new CustomerSignaturePoint(0.1, 0.2, 0),
                    new CustomerSignaturePoint(0.8, 0.9, 120)))));
  }

  /** Mutable capture holder kept private to one focused service fixture. */
  private record Fixture(CustomerBookingService service, CustomerCabinAcceptance[] captured) {
    CustomerCabinAcceptance saved() {
      return captured[0];
    }
  }
}
