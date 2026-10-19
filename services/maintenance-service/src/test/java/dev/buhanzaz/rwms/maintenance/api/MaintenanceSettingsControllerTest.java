package dev.buhanzaz.rwms.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.RepairCapacitySettingsService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class MaintenanceSettingsControllerTest {
  private final RepairCapacitySettingsService service = mock(RepairCapacitySettingsService.class);
  private final MaintenanceAuthorizer authorizer = mock(MaintenanceAuthorizer.class);
  private final MaintenanceSettingsController controller =
      new MaintenanceSettingsController(service, authorizer);
  private final Jwt jwt = mock(Jwt.class);

  @Test
  void getRequiresWarehouseManageBeforeDelegating() {
    UUID warehouseId = UUID.randomUUID();
    RepairCapacitySettingsResponse response =
        new RepairCapacitySettingsResponse(warehouseId, 0, 6, null, null);
    when(service.get(warehouseId)).thenReturn(response);

    assertThat(controller.get(jwt, warehouseId)).isEqualTo(response);

    InOrder calls = inOrder(authorizer, service);
    calls.verify(authorizer).requireManage(jwt, warehouseId);
    calls.verify(service).get(warehouseId);
  }

  @Test
  void putRequiresWarehouseManageBeforeDelegating() {
    UUID warehouseId = UUID.randomUUID();
    ReplaceRepairCapacitySettingsRequest request =
        new ReplaceRepairCapacitySettingsRequest(0L, 8);
    RepairCapacitySettingsResponse response =
        new RepairCapacitySettingsResponse(warehouseId, 0, 8, null, null);
    when(service.replace(warehouseId, request)).thenReturn(response);

    assertThat(controller.replace(jwt, warehouseId, request)).isEqualTo(response);

    InOrder calls = inOrder(authorizer, service);
    calls.verify(authorizer).requireManage(jwt, warehouseId);
    calls.verify(service).replace(warehouseId, request);
  }

  @Test
  void deniedManageAccessStopsTheCommand() {
    UUID warehouseId = UUID.randomUUID();
    ReplaceRepairCapacitySettingsRequest request =
        new ReplaceRepairCapacitySettingsRequest(0L, 8);
    doThrow(new AccessDeniedException("denied"))
        .when(authorizer)
        .requireManage(jwt, warehouseId);

    assertThatThrownBy(() -> controller.replace(jwt, warehouseId, request))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(service);
  }
}
