package dev.buhanzaz.rwms.logistics.customer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Verifies the fail-closed dedicated-client boundary for CustomerApp bearer tokens. */
class CustomerAuthorizerTest {
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000301");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000302");

  private final CustomerAuthorizer authorizer = new CustomerAuthorizer();

  @Test
  void acceptsOnlyDedicatedCustomerClientRoleAndScope() {
    CustomerIdentity identity = authorizer.identity(customerJwt("rwms-customer-android"));

    assertThat(identity.subjectId()).isEqualTo(SUBJECT);
    assertThat(identity.username()).isEqualTo("customer-301");
    assertThat(authorizer.orderActor(identity, WAREHOUSE).role()).isEqualTo("CUSTOMER");
    assertThat(authorizer.orderActor(identity, WAREHOUSE).editableWarehouses())
        .containsExactly(WAREHOUSE);
  }

  @Test
  void rejectsManagerClientEvenWhenItCarriesForgedCustomerClaims() {
    assertThatThrownBy(() -> authorizer.identity(customerJwt("rwms-manager-android")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Dedicated CustomerApp client");
  }

  @Test
  void rejectsMissingRoleScopeOrUserPrincipal() {
    assertThatThrownBy(
            () ->
                authorizer.identity(
                    jwt("USER", "RENTAL_MANAGER", List.of("customer.rental"),
                        "rwms-customer-android")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.identity(
                    jwt("USER", "CUSTOMER", List.of("rwms.read"),
                        "rwms-customer-android")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                authorizer.identity(
                    jwt("SERVICE", "CUSTOMER", List.of("customer.rental"),
                        "rwms-customer-android")))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt customerJwt(String clientId) {
    return jwt("USER", "CUSTOMER", List.of("customer.rental"), clientId);
  }

  private static Jwt jwt(
      String principalType, String role, List<String> scopes, String clientId) {
    return Jwt.withTokenValue("customer-token")
        .header("alg", "none")
        .subject(SUBJECT.toString())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", principalType)
        .claim("global_role", role)
        .claim("scope", scopes)
        .claim("client_id", clientId)
        .claim("preferred_username", " customer-301 ")
        .build();
  }
}
