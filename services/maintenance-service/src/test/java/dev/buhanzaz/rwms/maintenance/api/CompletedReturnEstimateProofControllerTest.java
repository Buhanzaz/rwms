package dev.buhanzaz.rwms.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.CompletedReturnEstimateProofReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class CompletedReturnEstimateProofControllerTest {
  private final CompletedReturnEstimateProofReader proofs =
      mock(CompletedReturnEstimateProofReader.class);
  private final MaintenanceAuthorizer authorizer =
      new MaintenanceAuthorizer(
          new MockEnvironment().withProperty("spring.profiles.active", "test"), false);
  private final CompletedReturnEstimateProofController controller =
      new CompletedReturnEstimateProofController(proofs, authorizer);

  @Test
  void allowsOnlyTheExactInventoryServicePrincipalAndScope() {
    UUID estimateId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.parse("2026-09-07T11:00:00Z");
    CompletedReturnEstimateProofResponse response =
        new CompletedReturnEstimateProofResponse(
            estimateId,
            1L,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            now.minusHours(1),
            now,
            "EMPTY",
            null);
    when(proofs.get(estimateId)).thenReturn(response);

    assertThat(controller.get(token("inventory-service", "inventory-service", "SERVICE",
        List.of("rwms-services"), "maintenance.inventory"), estimateId)).isSameAs(response);

    for (Jwt rejected : List.of(
        token("logistics-service", "logistics-service", "SERVICE", List.of("rwms-services"),
            "maintenance.inventory"),
        token("inventory-service", "inventory-service", "USER", List.of("rwms-services"),
            "maintenance.inventory"),
        token("inventory-service", "other-service", "SERVICE", List.of("rwms-services"),
            "maintenance.inventory"),
        token("inventory-service", "inventory-service", "SERVICE", List.of("rwms-services"),
            "maintenance.inventory rwms.read"),
        token("inventory-service", "inventory-service", "SERVICE", List.of("other"),
            "maintenance.inventory"))) {
      assertThatThrownBy(() -> controller.get(rejected, estimateId))
          .isInstanceOf(AccessDeniedException.class);
    }
    verify(proofs, times(1)).get(estimateId);
  }

  private static Jwt token(
      String subject,
      String clientId,
      String principalType,
      List<String> audience,
      String scope) {
    Instant now = Instant.parse("2026-09-07T12:00:00Z");
    return new Jwt(
        "token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "none"),
        Map.of(
            "sub", subject,
            "client_id", clientId,
            "principal_type", principalType,
            "aud", audience,
            "scope", scope));
  }
}
