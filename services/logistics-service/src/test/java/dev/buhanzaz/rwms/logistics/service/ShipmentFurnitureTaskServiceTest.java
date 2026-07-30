package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureReadinessState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.mapper.ShipmentFurnitureTaskResponseMapper;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderEquipmentRequirementRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentFurnitureTaskServiceTest {
  private static final UUID SHIPMENT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000009201");
  private static final UUID ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000009202");
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000009203");

  @Test
  void skipsFurniturePlanningWhenSavedOrderHasNoFurnitureRequirements() {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository documentLines =
        mock(LogisticsDocumentLineRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    RentalOrderEquipmentRequirementRepository requirements =
        mock(RentalOrderEquipmentRequirementRepository.class);
    ShipmentFurnitureMovementTaskRepository taskLinks =
        mock(ShipmentFurnitureMovementTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    EquipmentMovementTaskService movementTasks = mock(EquipmentMovementTaskService.class);
    ShipmentFurnitureTaskResponseMapper mapper =
        mock(ShipmentFurnitureTaskResponseMapper.class);

    LogisticsDocument shipment = mock(LogisticsDocument.class);
    when(shipment.getId()).thenReturn(SHIPMENT_ID);
    when(shipment.getVersion()).thenReturn(7L);
    when(shipment.getDocumentType()).thenReturn(LogisticsDocumentType.SHIPMENT);
    when(shipment.getRentalOrderId()).thenReturn(ORDER_ID);
    when(shipment.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(documents.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));

    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(orders.findWithClientById(ORDER_ID)).thenReturn(Optional.of(order));
    when(
            requirements
                .findAllByOrder_IdOrderByRentalItemIdAscEquipmentNameAscEquipmentIdAsc(ORDER_ID))
        .thenReturn(List.of());

    LogisticsDocumentLine shipmentLine = mock(LogisticsDocumentLine.class);
    when(documentLines.findAllByDocument_IdOrderByLineNumber(SHIPMENT_ID))
        .thenReturn(List.of(shipmentLine));
    when(taskLinks.findAllByDocument_IdOrderByUnitNumberAsc(SHIPMENT_ID))
        .thenReturn(List.of());

    ShipmentFurnitureTaskService service =
        new ShipmentFurnitureTaskService(
            documents,
            documentLines,
            orders,
            requirements,
            taskLinks,
            dependencies,
            movementTasks,
            mapper);

    var readiness = service.readiness(SHIPMENT_ID);

    assertThat(readiness.shipmentId()).isEqualTo(SHIPMENT_ID);
    assertThat(readiness.shipmentVersion()).isEqualTo(7);
    assertThat(readiness.state()).isEqualTo(ShipmentFurnitureReadinessState.NOT_REQUIRED);
    assertThat(readiness.tasks()).isEmpty();
    verifyNoInteractions(dependencies, movementTasks, mapper);
  }
}
