package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind.EQUIPMENT;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionFenceState.PREPARED;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionKind.LOSS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.CommandResult;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionFence;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PrepareMaintenancePropertyDispositionRequest;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class PropertyDispositionControllerTest {
  @Test
  void maintenanceOnlyControllerRejectsEveryOtherPrincipalBeforeServiceAccess() {
    PropertyDispositionService service = mock(PropertyDispositionService.class);
    PropertyDispositionController controller = controller(service);
    UUID warehouseId = UUID.randomUUID();

    assertThatThrownBy(
            () -> controller.snapshot(userJwt(), EQUIPMENT, UUID.randomUUID(), warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                controller.snapshot(
                    serviceJwt("maintenance-service", "inventory-service", "asset.maintenance"),
                    EQUIPMENT,
                    UUID.randomUUID(),
                    warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                controller.snapshot(
                    serviceJwt("maintenance-service", "maintenance-service", "asset.maintenance rwms.read"),
                    EQUIPMENT,
                    UUID.randomUUID(),
                    warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(service);
  }

  @Test
  void permanentDecisionReplayMapsToOkAndTheInitialPreparationToCreated() {
    PropertyDispositionService service = mock(PropertyDispositionService.class);
    PropertyDispositionController controller = controller(service);
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    PrepareMaintenancePropertyDispositionRequest request =
        new PrepareMaintenancePropertyDispositionRequest(
            warehouseId, EQUIPMENT, equipmentId, LOSS, null, 3L, 2L, null, null, null, List.of(), null);
    MaintenancePropertyDispositionFence fence = new MaintenancePropertyDispositionFence(
        decisionId,
        PREPARED,
        "a".repeat(64),
        warehouseId,
        EQUIPMENT,
        equipmentId,
        LOSS,
        null,
        null,
        List.of(),
        OffsetDateTime.now(),
        null);
    UUID serviceSubject = UUID.nameUUIDFromBytes(
        "service:maintenance-service".getBytes(StandardCharsets.UTF_8));
    when(service.prepare(eq(serviceSubject), eq(decisionId), eq(idempotencyKey), eq(request)))
        .thenReturn(new CommandResult<>(fence, false));

    var created = controller.prepare(
        serviceJwt("maintenance-service", "maintenance-service", "asset.maintenance"),
        decisionId,
        idempotencyKey,
        request);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).isEqualTo(fence);
    verify(service).prepare(serviceSubject, decisionId, idempotencyKey, request);

    when(service.prepare(eq(serviceSubject), eq(decisionId), eq(idempotencyKey), eq(request)))
        .thenReturn(new CommandResult<>(fence, true));
    var replayed = controller.prepare(
        serviceJwt("maintenance-service", "maintenance-service", "asset.maintenance"),
        decisionId,
        idempotencyKey,
        request);
    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replayed.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
  }

  private static PropertyDispositionController controller(PropertyDispositionService service) {
    return new PropertyDispositionController(
        service, new AssetAuthorizer(new MockEnvironment(), false));
  }

  private static Jwt serviceJwt(String subject, String clientId, String scope) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub", subject,
            "principal_type", "SERVICE",
            "client_id", clientId,
            "scope", scope));
  }

  private static Jwt userJwt() {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub", UUID.randomUUID().toString(),
            "principal_type", "USER",
            "scope", "asset.maintenance"));
  }
}
