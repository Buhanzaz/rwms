package dev.buhanzaz.rwms.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.api.WarehouseOperationMarkRecoveryApiModels.WarehouseOperationMarkRecoveryRequest;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkRecoveryService;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class WarehouseOperationMarkRecoveryControllerTest {
  private final WarehouseOperationMarkRecoveryService recovery =
      mock(WarehouseOperationMarkRecoveryService.class);
  private final MaintenanceAuthorizer access = mock(MaintenanceAuthorizer.class);
  private final WarehouseOperationMarkRecoveryController controller =
      new WarehouseOperationMarkRecoveryController(recovery, access);

  @Test
  void exposesExactReplayTruthAfterAdministratorAuthorization() {
    Jwt jwt = mock(Jwt.class);
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    OffsetDateTime recoveredAt = OffsetDateTime.now(ZoneOffset.UTC);
    when(access.subjectId(jwt)).thenReturn(reviewer);
    when(recovery.recover(warehouseId, operationId, 3, reviewer, "reviewed"))
        .thenReturn(
            new WarehouseOperationMarkStore.RecoveryResult(
                warehouseId,
                operationId,
                "PENDING",
                0,
                4,
                "HTTP_503",
                reviewer,
                "reviewed",
                recoveredAt,
                true));

    var response = controller.recover(
        jwt,
        operationId,
        warehouseId,
        new WarehouseOperationMarkRecoveryRequest(3L, "reviewed"));

    verify(access).requireWarehouseOperationRecoveryAdministrator(jwt, warehouseId);
    assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().recoveryVersion()).isEqualTo(4);
  }

  @Test
  void deniedCallerCannotReachRecoveryService() {
    Jwt jwt = mock(Jwt.class);
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new AccessDeniedException("denied"))
        .when(access)
        .requireWarehouseOperationRecoveryAdministrator(jwt, warehouseId);

    assertThatThrownBy(
            () ->
                controller.recover(
                    jwt,
                    operationId,
                    warehouseId,
                    new WarehouseOperationMarkRecoveryRequest(0L, "reviewed")))
        .isInstanceOf(AccessDeniedException.class);
    verify(recovery, never())
        .recover(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
  }
}
