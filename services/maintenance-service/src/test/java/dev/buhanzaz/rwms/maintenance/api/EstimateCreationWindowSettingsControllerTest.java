package dev.buhanzaz.rwms.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.EstimateCreationWindowSettingsService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class EstimateCreationWindowSettingsControllerTest {
  private final EstimateCreationWindowSettingsService service =
      mock(EstimateCreationWindowSettingsService.class);
  private final MaintenanceAuthorizer authorizer = mock(MaintenanceAuthorizer.class);
  private final EstimateCreationWindowSettingsController controller =
      new EstimateCreationWindowSettingsController(service, authorizer);
  private final Jwt jwt = mock(Jwt.class);

  @Test
  void getAndPutRequireManageBeforeDelegating() {
    UUID warehouseId = UUID.randomUUID();
    ReplaceEstimateCreationWindowSettingsRequest request =
        new ReplaceEstimateCreationWindowSettingsRequest(0L, 7);
    EstimateCreationWindowSettingsResponse response =
        new EstimateCreationWindowSettingsResponse(warehouseId, 0, 7, null, null);
    when(service.get(warehouseId)).thenReturn(response);
    when(service.replace(warehouseId, request)).thenReturn(response);

    assertThat(controller.get(jwt, warehouseId)).isSameAs(response);
    assertThat(controller.replace(jwt, warehouseId, request)).isSameAs(response);

    InOrder calls = inOrder(authorizer, service);
    calls.verify(authorizer).requireManage(jwt, warehouseId);
    calls.verify(service).get(warehouseId);
    calls.verify(authorizer).requireManage(jwt, warehouseId);
    calls.verify(service).replace(warehouseId, request);
  }

  @Test
  void deniedManageAccessStopsRead() {
    UUID warehouseId = UUID.randomUUID();
    doThrow(new AccessDeniedException("denied"))
        .when(authorizer)
        .requireManage(jwt, warehouseId);

    assertThatThrownBy(() -> controller.get(jwt, warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(service);
  }
}
