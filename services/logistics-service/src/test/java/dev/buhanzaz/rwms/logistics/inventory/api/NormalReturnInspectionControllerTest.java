package dev.buhanzaz.rwms.logistics.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.inventory.api.NormalReturnInspectionApiModels.NormalReturnInspectionResponse;
import dev.buhanzaz.rwms.logistics.inventory.service.NormalReturnInspectionService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class NormalReturnInspectionControllerTest {
  private final NormalReturnInspectionService service = mock(NormalReturnInspectionService.class);
  private final NormalReturnInspectionController controller =
      new NormalReturnInspectionController(
          new LogisticsAuthorizer(new MockEnvironment(), false), service);

  @Test
  void exactInventoryServiceTokenCanReadInspectionProof() {
    UUID returnId = UUID.randomUUID();
    NormalReturnInspectionResponse response =
        new NormalReturnInspectionResponse(
            returnId,
            1,
            UUID.randomUUID(),
            OffsetDateTime.parse("2026-08-01T09:00:00Z"),
            OffsetDateTime.parse("2026-08-01T09:15:00Z"),
            dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState.ACCEPTED,
            List.of());
    when(service.get(returnId)).thenReturn(response);

    assertThat(controller.get(inventoryJwt(), returnId)).isSameAs(response);
  }

  @Test
  void wrongServicePrincipalIsRejectedBeforeQuerying() {
    UUID returnId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                controller.get(
                    jwt(
                        "maintenance-service",
                        "maintenance-service",
                        "SERVICE",
                        List.of("logistics.inventory")),
                    returnId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                controller.get(
                    jwt(
                        "inventory-service",
                        "inventory-service",
                        "USER",
                        List.of("logistics.inventory")),
                    returnId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                controller.get(
                    jwt(
                        "inventory-service",
                        "inventory-service",
                        "SERVICE",
                        List.of("rwms.read")),
                    returnId))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                controller.get(
                    jwt(
                        "inventory-service",
                        "inventory-service",
                        "SERVICE",
                        List.of("logistics.inventory", "rwms.read")),
                    returnId))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(service);
  }

  private static Jwt inventoryJwt() {
    return jwt(
        "inventory-service",
        "inventory-service",
        "SERVICE",
        List.of("logistics.inventory"));
  }

  private static Jwt jwt(
      String subject, String clientId, String principalType, List<String> scopes) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .audience(List.of("rwms-services"))
        .claim("principal_type", principalType)
        .claim("client_id", clientId)
        .claim("scope", scopes)
        .build();
  }
}
