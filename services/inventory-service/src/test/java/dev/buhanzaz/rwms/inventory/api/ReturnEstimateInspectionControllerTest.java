package dev.buhanzaz.rwms.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.api.ReturnEstimateInspectionResponse.State;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.inventory.service.ReturnEstimateInspectionReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class ReturnEstimateInspectionControllerTest {
  private final ReturnEstimateInspectionReader inspections =
      mock(ReturnEstimateInspectionReader.class);
  private final InventoryAuthorizer access = mock(InventoryAuthorizer.class);
  private final ReturnEstimateInspectionController controller =
      new ReturnEstimateInspectionController(inspections, access);

  @Test
  void authorizesWarehouseReadBeforeResolvingStatus() {
    Jwt jwt = mock(Jwt.class);
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    ReturnEstimateInspectionResponse response =
        new ReturnEstimateInspectionResponse(State.NOT_REQUIRED, null, null, null);
    when(inspections.get(estimateId, warehouseId)).thenReturn(response);

    assertThat(controller.get(jwt, estimateId, warehouseId)).isSameAs(response);

    InOrder calls = inOrder(access, inspections);
    calls.verify(access).requireRead(jwt, warehouseId);
    calls.verify(inspections).get(estimateId, warehouseId);
  }

  @Test
  void deniedWarehouseReadCannotReachLocalOrRemoteStatusResolution() {
    Jwt jwt = mock(Jwt.class);
    UUID estimateId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    doThrow(new AccessDeniedException("denied")).when(access).requireRead(jwt, warehouseId);

    assertThatThrownBy(() -> controller.get(jwt, estimateId, warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(inspections);
  }
}
