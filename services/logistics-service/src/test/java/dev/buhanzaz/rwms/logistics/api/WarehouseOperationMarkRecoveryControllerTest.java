package dev.buhanzaz.rwms.logistics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.WarehouseOperationMarkRecoveryApiModels.WarehouseOperationMarkRecoveryRequest;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseOperationMarkStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class WarehouseOperationMarkRecoveryControllerTest {
  private final LogisticsWarehouseOperationMarkStore marks =
      mock(LogisticsWarehouseOperationMarkStore.class);
  private final LogisticsAuthorizer access = mock(LogisticsAuthorizer.class);
  private final WarehouseOperationMarkRecoveryController controller =
      new WarehouseOperationMarkRecoveryController(marks, access);

  @Test
  void exposesExactReplayTruthAfterAdministratorAuthorization() {
    Jwt jwt = mock(Jwt.class);
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    OffsetDateTime recoveredAt = OffsetDateTime.now(ZoneOffset.UTC);
    when(access.subjectId(jwt)).thenReturn(reviewer);
    when(marks.recoverQuarantined(warehouseId, operationId, 3, reviewer, "reviewed"))
        .thenReturn(
            new LogisticsWarehouseOperationMarkStore.RecoveryResult(
                warehouseId,
                operationId,
                "PENDING",
                0,
                4,
                "WAREHOUSE_UNAVAILABLE",
                reviewer,
                "reviewed",
                recoveredAt,
                true));

    var response =
        controller.recover(
            jwt,
            operationId,
            warehouseId,
            new WarehouseOperationMarkRecoveryRequest(3L, "reviewed"));

    verify(access).requireWarehouseOperationRecoveryAdministrator(jwt);
    assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().recoveryVersion()).isEqualTo(4);
  }

  @Test
  void deniedCallerCannotReachRecoveryStore() {
    Jwt jwt = mock(Jwt.class);
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new AccessDeniedException("denied"))
        .when(access)
        .requireWarehouseOperationRecoveryAdministrator(jwt);

    assertThatThrownBy(
            () ->
                controller.recover(
                    jwt,
                    operationId,
                    warehouseId,
                    new WarehouseOperationMarkRecoveryRequest(0L, "reviewed")))
        .isInstanceOf(AccessDeniedException.class);
    verify(marks, never())
        .recoverQuarantined(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
  }
}
