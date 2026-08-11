package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.ShipmentFurnitureTaskService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies bounded board enrichment independently of the strict driver-task detail read. */
class DriverTripProjectionServiceTest {
  @Test
  void twoTripsOfOneOrderShareExactlyOneAssetCompositionRead() {
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderEquipmentRequirementRepository requirements =
        mock(RentalOrderEquipmentRequirementRepository.class);
    ShipmentFurnitureMovementTaskRepository links =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    EquipmentMovementTaskRepository movements = mock(EquipmentMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    ShipmentFurnitureTaskService strictReadiness = mock(ShipmentFurnitureTaskService.class);
    DriverTripProjectionService service =
        new DriverTripProjectionService(
            driverTasks,
            documents,
            orders,
            requirements,
            links,
            movements,
            dependencies,
            strictReadiness);
    UUID orderId = UUID.randomUUID();
    UUID firstDocumentId = UUID.randomUUID();
    UUID secondDocumentId = UUID.randomUUID();
    LogisticsDocument firstDocument = document(firstDocumentId, orderId);
    LogisticsDocument secondDocument = document(secondDocumentId, orderId);
    DriverLogisticsTask first = task(firstDocumentId, 1);
    DriverLogisticsTask second = task(secondDocumentId, 2);
    RentalOrder order = mock(RentalOrder.class);
    OrderClient client = mock(OrderClient.class);
    when(order.getId()).thenReturn(orderId);
    when(order.getClient()).thenReturn(client);
    when(order.getAdditionalContacts()).thenReturn(List.of());
    when(order.getDesiredDeliveryWindows()).thenReturn(List.of());
    when(client.getAdditionalContacts()).thenReturn(List.of());
    when(client.getDisplayName()).thenReturn("ООО Север");
    when(client.getPhone()).thenReturn("+79990000000");
    when(documents.findAllById(List.of(firstDocumentId, secondDocumentId)))
        .thenReturn(List.of(firstDocument, secondDocument));
    when(orders.findAllWithClientByIdIn(List.of(orderId))).thenReturn(List.of(order));
    when(requirements
            .findAllByOrder_IdInOrderByOrder_IdAscRentalItemIdAscEquipmentNameAscEquipmentIdAsc(
                Set.of(orderId)))
        .thenReturn(List.of());
    when(links.findAllByDocument_IdInOrderByDocument_IdAscUnitNumberAsc(
            Set.of(firstDocumentId, secondDocumentId)))
        .thenReturn(List.of());
    when(movements.findAllById(List.of())).thenReturn(List.of());
    when(dependencies.readOrderUnits(orderId)).thenReturn(List.of());

    var result = service.boardDetails(List.of(first, second));

    assertThat(result).containsKeys(first.getId(), second.getId());
    assertThat(result.values()).doesNotContainNull();
    verify(dependencies, times(1)).readOrderUnits(orderId);
    verifyNoInteractions(strictReadiness);
  }

  private static LogisticsDocument document(UUID documentId, UUID orderId) {
    LogisticsDocument document = mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(documentId);
    when(document.getRentalOrderId()).thenReturn(orderId);
    return document;
  }

  private static DriverLogisticsTask task(UUID documentId, int tripNumber) {
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    when(task.getId()).thenReturn(UUID.randomUUID());
    when(task.isGroupedDocument()).thenReturn(true);
    when(task.getSourceId()).thenReturn(documentId);
    when(task.getTripNumber()).thenReturn(tripNumber);
    when(task.getKind()).thenReturn(DriverTaskKind.SHIPMENT);
    when(task.getMembers()).thenReturn(List.of());
    return task;
  }
}
