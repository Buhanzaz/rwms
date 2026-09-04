package dev.buhanzaz.rwms.logistics.driver.settings.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.settings.api.ShipmentTaskSettingsApiModels.ShipmentTaskSettingsResponse;
import dev.buhanzaz.rwms.logistics.driver.settings.api.ShipmentTaskSettingsApiModels.UpdateShipmentTaskSettingsRequest;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class ShipmentTaskSettingsControllerTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000301");

  private final ShipmentTaskSettingsService settings = mock(ShipmentTaskSettingsService.class);
  private final LogisticsAuthorizer access = mock(LogisticsAuthorizer.class);
  private final ShipmentTaskSettingsController controller =
      new ShipmentTaskSettingsController(settings, access);

  @Test
  void readsAndUpdatesSettingsForTheAuthorizedWarehouse() {
    Jwt jwt = principal();
    UpdateShipmentTaskSettingsRequest request = new UpdateShipmentTaskSettingsRequest(4L, 3);
    ShipmentTaskSettingsResponse response =
        new ShipmentTaskSettingsResponse(
            WAREHOUSE, 5L, 3, ACTOR, OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC));
    when(access.subjectId(jwt)).thenReturn(ACTOR);
    when(settings.get(WAREHOUSE, ACTOR)).thenReturn(response);
    when(settings.update(WAREHOUSE, ACTOR, request)).thenReturn(response);

    assertThat(controller.get(jwt, WAREHOUSE)).isEqualTo(response);
    assertThat(controller.update(jwt, WAREHOUSE, request)).isEqualTo(response);

    verify(access).requireRead(jwt, WAREHOUSE);
    verify(access).requireManage(jwt, WAREHOUSE);
    verify(settings).get(WAREHOUSE, ACTOR);
    verify(settings).update(WAREHOUSE, ACTOR, request);
  }

  private static Jwt principal() {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(ACTOR.toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", "USER")
        .claim("roles", List.of("SYSTEM_ADMIN"))
        .build();
  }
}
