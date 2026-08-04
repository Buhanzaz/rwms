package dev.buhanzaz.rwms.logistics.equipment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.CreateMaintenanceEquipmentMovementTaskRequest;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.MaintenanceEquipmentMovementLineRequest;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskOwnerType;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskProcessor;
import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class MaintenanceEquipmentMovementTaskControllerTest {
  private final EquipmentMovementTaskService service = mock(EquipmentMovementTaskService.class);
  private final EquipmentMovementTaskProcessor processor = mock(EquipmentMovementTaskProcessor.class);
  private final MaintenanceEquipmentMovementTaskController controller =
      new MaintenanceEquipmentMovementTaskController(
          service, processor, new LogisticsAuthorizer(new MockEnvironment(), false));

  @Test
  void exactMaintenanceServiceTokenCanCreateAndReadItsPrivateMovement() {
    CreateMaintenanceEquipmentMovementTaskRequest request = request();
    EquipmentMovementTaskResponse response = response(request.decisionId());
    UUID idempotencyKey = UUID.randomUUID();
    when(service.createFromMaintenance(idempotencyKey, request))
        .thenReturn(new EquipmentMovementTaskService.CreateResult(response, false));
    when(service.getMaintenance(response.id())).thenReturn(response);

    var created = controller.create(maintenanceJwt(), idempotencyKey, request);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getHeaders().getETag()).isEqualTo("\"7\"");
    assertThat(created.getBody()).isSameAs(response);
    assertThat(controller.get(maintenanceJwt(), response.id())).isSameAs(response);
    verify(service).createFromMaintenance(idempotencyKey, request);
    verify(processor).processUntilIdle(response.id());
    verify(service, org.mockito.Mockito.times(2)).getMaintenance(response.id());
  }

  @Test
  void browserTokenCannotReachTheMaintenanceMovementBoundary() {
    assertThatThrownBy(() -> controller.get(browserJwt(), UUID.randomUUID()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("maintenance-service");

    verifyNoInteractions(service, processor);
  }

  @Test
  void serviceTokenWithAnExtraScopeIsRejectedRatherThanPartiallyAccepted() {
    assertThatThrownBy(
            () ->
                controller.get(
                    serviceJwt(
                        List.of("logistics.maintenance", "rwms.write"), List.of("rwms-services")),
                    UUID.randomUUID()))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(service, processor);
  }

  private static CreateMaintenanceEquipmentMovementTaskRequest request() {
    return new CreateMaintenanceEquipmentMovementTaskRequest(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "БЫТ-901",
        30,
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
        List.of(
            new MaintenanceEquipmentMovementLineRequest(
                UUID.randomUUID(), UUID.randomUUID(), 2L, 1L)));
  }

  private static EquipmentMovementTaskResponse response(UUID decisionId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new EquipmentMovementTaskResponse(
        UUID.randomUUID(),
        7L,
        UUID.randomUUID(),
        EquipmentMovementTaskOwnerType.MAINTENANCE_DISPOSITION,
        decisionId,
        UUID.randomUUID(),
        null,
        null,
        null,
        "БЫТ-901",
        30,
        now.plusHours(1),
        EquipmentMovementTaskState.AWAITING_WORKER,
        null,
        null,
        List.of(),
        now,
        now);
  }

  private static Jwt maintenanceJwt() {
    return serviceJwt(List.of("logistics.maintenance"), List.of("rwms-services"));
  }

  private static Jwt serviceJwt(List<String> scopes, List<String> audience) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("maintenance-service")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .audience(audience)
        .claim("principal_type", "SERVICE")
        .claim("client_id", "maintenance-service")
        .claim("scope", scopes)
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
