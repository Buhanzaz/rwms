package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliverySlotStore;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ApplyPlanningAssignmentsRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningAssignmentRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies that planning responses use only the durable document-owned shipment task identity. */
class RentalOrderPlanningExternalTaskIdentityTest {
  private final DriverLogisticsTaskRepository driverTasks =
      mock(DriverLogisticsTaskRepository.class);
  private final RentalOrderPlanningIntegrationService service =
      new RentalOrderPlanningIntegrationService(
          mock(RentalOrderRepository.class),
          mock(RentalOrderReadService.class),
          mock(LogisticsDocumentLineRepository.class),
          mock(LogisticsDocumentRepository.class),
          driverTasks,
          mock(RentalOrderService.class),
          mock(LogisticsWarehouseLifecycle.class),
          mock(LogisticsDependencyGateway.class),
          mock(CustomerDeliverySlotStore.class),
          mock(TransferRouteCargoEnricher.class));

  @Test
  void resolvesTheExactShipmentExternalTaskWithoutDerivingItFromTheDocument() {
    UUID documentId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    DriverLogisticsTask shipment = task(DriverTaskKind.SHIPMENT, externalTaskId);
    DriverLogisticsTask anotherKind = task(DriverTaskKind.RETURN, UUID.randomUUID());
    when(shipment.getVersion()).thenReturn(7L);
    when(driverTasks.findAllBySourceTypeAndSourceIdIn(
            DriverTaskSourceType.LOGISTICS_DOCUMENT, List.of(documentId)))
        .thenReturn(List.of(anotherKind, shipment));
    PlanningAssignmentRequest assignment = mock(PlanningAssignmentRequest.class);

    Object resolved =
        ReflectionTestUtils.invokeMethod(
            service,
            "exactExternalTaskIdentity",
            documentId,
            mock(ApplyPlanningAssignmentsRequest.class),
            assignment);

    assertThat((UUID) ReflectionTestUtils.invokeMethod(resolved, "externalTaskId"))
        .isEqualTo(externalTaskId)
        .isNotEqualTo(documentId);
    assertThat((Long) ReflectionTestUtils.invokeMethod(resolved, "version")).isEqualTo(7L);
  }

  @Test
  void rejectsAbsentAmbiguousOrNullExternalShipmentIdentity() {
    UUID documentId = UUID.randomUUID();
    when(driverTasks.findAllBySourceTypeAndSourceIdIn(any(), any()))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                task(DriverTaskKind.SHIPMENT, UUID.randomUUID()),
                task(DriverTaskKind.SHIPMENT, UUID.randomUUID())))
        .thenReturn(List.of(task(DriverTaskKind.SHIPMENT, null)));
    ApplyPlanningAssignmentsRequest request = mock(ApplyPlanningAssignmentsRequest.class);
    PlanningAssignmentRequest assignment = mock(PlanningAssignmentRequest.class);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    service, "exactExternalTaskIdentity", documentId, request, assignment))
        .isInstanceOf(LogisticsConflictException.class);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    service, "exactExternalTaskIdentity", documentId, request, assignment))
        .isInstanceOf(LogisticsConflictException.class);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    service, "exactExternalTaskIdentity", documentId, request, assignment))
        .isInstanceOf(LogisticsConflictException.class);
  }

  private static DriverLogisticsTask task(DriverTaskKind kind, UUID externalTaskId) {
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    when(task.getKind()).thenReturn(kind);
    when(task.getExternalTaskId()).thenReturn(externalTaskId);
    return task;
  }
}
