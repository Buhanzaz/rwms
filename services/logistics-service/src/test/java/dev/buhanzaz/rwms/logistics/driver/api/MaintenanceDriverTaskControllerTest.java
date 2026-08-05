package dev.buhanzaz.rwms.logistics.driver.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.MaintenanceDriverTaskCompensationOutcome;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.MaintenanceDriverTaskCompensationResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.service.DriverQueueScheduler;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskProcessor;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskService;
import dev.buhanzaz.rwms.logistics.driver.service.MaintenanceDriverTaskCompensationService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class MaintenanceDriverTaskControllerTest {
  private final DriverTaskService driverTasks = mock(DriverTaskService.class);
  private final DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
  private final DriverQueueScheduler scheduler = mock(DriverQueueScheduler.class);
  private final MaintenanceDriverTaskCompensationService compensation =
      mock(MaintenanceDriverTaskCompensationService.class);
  private final LogisticsWarehouseLifecycle warehouseLifecycle =
      mock(LogisticsWarehouseLifecycle.class);
  private final MaintenanceDriverTaskController controller =
      new MaintenanceDriverTaskController(
          driverTasks,
          processor,
          scheduler,
          compensation,
          new LogisticsAuthorizer(new MockEnvironment(), false),
          warehouseLifecycle);

  @Test
  void exactMaintenanceServiceTokenCanLookupAndCancelThePrivateBoundary() {
    UUID repairId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    MaintenanceDriverTaskCompensationResponse response =
        new MaintenanceDriverTaskCompensationResponse(
            repairId,
            DriverTaskKind.DELIVER_TO_REPAIR,
            MaintenanceDriverTaskCompensationOutcome.ABSENT,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(compensation.lookup(repairId, DriverTaskKind.DELIVER_TO_REPAIR)).thenReturn(response);
    when(compensation.cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey))
        .thenReturn(response);

    assertThat(
            controller.lookupByRepair(
                maintenanceJwt(), repairId, DriverTaskKind.DELIVER_TO_REPAIR))
        .isSameAs(response);
    assertThat(
            controller
                .cancelByRepair(
                    maintenanceJwt(), repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    verify(compensation).lookup(repairId, DriverTaskKind.DELIVER_TO_REPAIR);
    verify(compensation).cancel(repairId, DriverTaskKind.DELIVER_TO_REPAIR, idempotencyKey);
  }

  @Test
  void browserUserCannotReachTheMaintenanceCompensationBoundary() {
    assertThatThrownBy(
            () ->
                controller.lookupByRepair(
                    browserJwt(), UUID.randomUUID(), DriverTaskKind.DELIVER_TO_REPAIR))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("maintenance-service");
    verifyNoInteractions(compensation);
  }

  private static Jwt maintenanceJwt() {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("maintenance-service")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .audience(List.of("rwms-services"))
        .claim("principal_type", "SERVICE")
        .claim("client_id", "maintenance-service")
        .claim("scope", List.of("logistics.maintenance"))
        .build();
  }

  private static Jwt browserJwt() {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(UUID.randomUUID().toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", "USER")
        .claim("scope", "rwms.write")
        .build();
  }
}
