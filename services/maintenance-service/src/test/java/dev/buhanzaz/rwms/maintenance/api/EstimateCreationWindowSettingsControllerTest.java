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
  void getRequiresGlobalReadAndPutRequiresGlobalManageBeforeDelegating() {
    ReplaceEstimateCreationWindowSettingsRequest request =
        new ReplaceEstimateCreationWindowSettingsRequest(0L, 7);
    EstimateCreationWindowSettingsResponse response =
        new EstimateCreationWindowSettingsResponse(0, 7, null, null);
    when(service.get()).thenReturn(response);
    when(service.replace(request)).thenReturn(response);

    assertThat(controller.get(jwt)).isSameAs(response);
    assertThat(controller.replace(jwt, request)).isSameAs(response);

    InOrder calls = inOrder(authorizer, service);
    calls.verify(authorizer).requireGlobalRead(jwt);
    calls.verify(service).get();
    calls.verify(authorizer).requireGlobalManage(jwt);
    calls.verify(service).replace(request);
  }

  @Test
  void deniedGlobalAccessStopsRead() {
    doThrow(new AccessDeniedException("denied"))
        .when(authorizer)
        .requireGlobalRead(jwt);

    assertThatThrownBy(() -> controller.get(jwt))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(service);
  }
}
