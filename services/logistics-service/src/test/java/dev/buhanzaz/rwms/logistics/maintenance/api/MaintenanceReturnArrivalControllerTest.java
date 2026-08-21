package dev.buhanzaz.rwms.logistics.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.maintenance.service.MaintenanceReturnArrivalService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class MaintenanceReturnArrivalControllerTest {
  private final MaintenanceReturnArrivalService service =
      mock(MaintenanceReturnArrivalService.class);
  private final MaintenanceReturnArrivalController controller =
      new MaintenanceReturnArrivalController(
          service, new LogisticsAuthorizer(new MockEnvironment(), false));

  @Test
  void exactMaintenanceServiceTokenCanReadTheMinimalArrivalProjection() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    MaintenanceReturnArrivalResponse response =
        new MaintenanceReturnArrivalResponse(
            warehouseId,
            rentalItemId,
            UUID.randomUUID(),
            OffsetDateTime.parse("2026-08-01T09:00:00Z"));
    when(service.latest(warehouseId, rentalItemId)).thenReturn(response);

    assertThat(controller.latest(maintenanceJwt(), rentalItemId, warehouseId)).isSameAs(response);
  }

  @Test
  void browserAndExtraScopedServiceTokensAreRejectedBeforeQuerying() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();

    assertThatThrownBy(() -> controller.latest(browserJwt(), rentalItemId, warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                controller.latest(
                    serviceJwt(List.of("logistics.maintenance", "rwms.read")),
                    rentalItemId,
                    warehouseId))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(service);
  }

  private static Jwt maintenanceJwt() {
    return serviceJwt(List.of("logistics.maintenance"));
  }

  private static Jwt serviceJwt(List<String> scopes) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("maintenance-service")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .audience(List.of("rwms-services"))
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
        .claim("scope", "rwms.read")
        .build();
  }
}
