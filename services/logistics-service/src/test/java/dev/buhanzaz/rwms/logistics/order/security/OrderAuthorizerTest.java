package dev.buhanzaz.rwms.logistics.order.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class OrderAuthorizerTest {
  private static final UUID SUBJECT =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final OrderAuthorizer authorizer = new OrderAuthorizer(new MockEnvironment(), false);

  @Test
  void acceptsRentalEntitledStaffFromTheDedicatedManagerClient() {
    for (String role :
        List.of("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER", "RENTAL_MANAGER", "VIEWER")) {
      OrderActor actor =
          authorizer.readActor(
              jwt(role, "USER", "rwms-rental-manager-web", "rental.manage", true));

      assertThat(actor.subjectId()).isEqualTo(SUBJECT);
      assertThat(actor.role()).isEqualTo(role);
      assertThat(actor.rentalAccess()).isTrue();
      assertThat(actor.writeScope()).isTrue();
    }
  }

  @Test
  void rejectsInvalidDedicatedManagerCredentials() {
    for (Jwt invalid :
        List.of(
            jwt("CUSTOMER", "USER", "rwms-rental-manager-web", "rental.manage", true),
            jwt("SYSTEM_ADMIN", "SERVICE", "rwms-rental-manager-web", "rental.manage", true),
            jwt("SYSTEM_ADMIN", "USER", "rwms-rental-manager-web", "rental.manage", false),
            jwt(
                "SYSTEM_ADMIN",
                "USER",
                "rwms-rental-manager-web",
                "rental.manage rwms.read",
                true))) {
      assertThatThrownBy(() -> authorizer.readActor(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void rentalManagerRoleRemainsIsolatedFromThePanelClient() {
    assertThatThrownBy(
            () ->
                authorizer.readActor(
                    jwt("RENTAL_MANAGER", "USER", "rwms-panel", "rwms.read", true)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Dedicated rental manager client");
  }

  private static Jwt jwt(
      String role, String principalType, String clientId, String scope, boolean rentalAccess) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(SUBJECT.toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("global_role", role)
        .claim("principal_type", principalType)
        .claim("client_id", clientId)
        .claim("scope", scope)
        .claim("rentalAccess", rentalAccess)
        .build();
  }
}
