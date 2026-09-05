package dev.buhanzaz.rwms.asset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.RentalItemReserveReadService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class RentalItemReserveControllerAuthorizationTest {
  private final RentalItemReserveReadService reads = mock(RentalItemReserveReadService.class);
  private final RentalItemReserveController controller =
      new RentalItemReserveController(reads, new AssetAuthorizer(new MockEnvironment(), false));

  @Test
  void rejectsUserOtherServiceSubjectClientOrBroaderScopeBeforeReadingProvenance() {
    for (Jwt jwt :
        List.of(
            token("USER", "logistics-service", "logistics-service", "asset.logistics"),
            token("SERVICE", "other-service", "logistics-service", "asset.logistics"),
            token("SERVICE", "logistics-service", "other-service", "asset.logistics"),
            token("SERVICE", "logistics-service", "logistics-service", "rwms.read"),
            token(
                "SERVICE",
                "logistics-service",
                "logistics-service",
                "asset.logistics rwms.read"))) {
      assertThatThrownBy(() -> controller.read(jwt, UUID.randomUUID(), UUID.randomUUID()))
          .isInstanceOf(AccessDeniedException.class);
    }
    assertThatThrownBy(() -> controller.read(null, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(reads);
  }

  @Test
  void exactLogisticsCredentialGetsUncacheableSnapshot() {
    UUID cabin = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    var snapshot =
        new RentalItemReserveSnapshot(cabin, warehouse, OffsetDateTime.now(), List.of(), null);
    when(reads.read(cabin, warehouse)).thenReturn(snapshot);

    var response =
        controller.read(
            token("SERVICE", "logistics-service", "logistics-service", "asset.logistics"),
            cabin,
            warehouse);

    assertThat(response.getBody()).isSameAs(snapshot);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  private static Jwt token(String type, String client, String subject, String scope) {
    return new Jwt(
        "test",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of("sub", subject, "client_id", client, "principal_type", type, "scope", scope));
  }
}
