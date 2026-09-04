package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class AssistantAuthorizerTest {
  private static final UUID SUBJECT =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final AssistantAuthorizer authorizer = new AssistantAuthorizer();

  @Test
  void acceptsOnlyDedicatedWebAndAndroidManagerApplications() {
    for (String clientId :
        List.of("rwms-rental-manager-web", "rwms-rental-manager-android")) {
      UUID subject =
          authorizer.requireRentalManager(
              jwt(
                  SUBJECT.toString(),
                  clientId,
                  "RENTAL_MANAGER",
                  "USER",
                  "openid profile rental.manage",
                  true));

      assertThat(subject).isEqualTo(SUBJECT);
    }
  }

  @Test
  void rejectsPanelUnknownMixedScopeAndWrongRoleTokens() {
    assertDenied(
        jwt(
            SUBJECT.toString(),
            "rwms-panel",
            "RENTAL_MANAGER",
            "USER",
            "rwms.read rwms.write",
            true));
    assertDenied(
        jwt(
            SUBJECT.toString(),
            "unknown-client",
            "RENTAL_MANAGER",
            "USER",
            "rental.manage",
            true));
    assertDenied(
        jwt(
            SUBJECT.toString(),
            "rwms-rental-manager-web",
            "RENTAL_MANAGER",
            "USER",
            "rental.manage warehouse.read",
            true));
    assertDenied(
        jwt(
            SUBJECT.toString(),
            "rwms-rental-manager-web",
            "SYSTEM_ADMIN",
            "USER",
            "rental.manage",
            true));
  }

  @Test
  void rejectsMalformedSubjectAndInvalidApplicationClaims() {
    assertDenied(
        jwt(
            "not-a-uuid",
            "rwms-rental-manager-web",
            "RENTAL_MANAGER",
            "USER",
            "rental.manage",
            true));
    assertDenied(
        jwt(
            SUBJECT.toString(),
            "rwms-rental-manager-web",
            "RENTAL_MANAGER",
            "SERVICE",
            "rental.manage",
            true));
    assertDenied(
        jwt(
            SUBJECT.toString(),
            "rwms-rental-manager-web",
            "RENTAL_MANAGER",
            "USER",
            "rental.manage",
            false));
  }

  private void assertDenied(Jwt jwt) {
    assertThatThrownBy(() -> authorizer.requireRentalManager(jwt))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt jwt(
      String subject,
      String clientId,
      String role,
      String principalType,
      String scope,
      boolean rentalAccess) {
    Jwt.Builder builder =
        Jwt.withTokenValue("assistant-token")
            .header("alg", "none")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("client_id", clientId)
            .claim("global_role", role)
            .claim("principal_type", principalType)
            .claim("scope", scope)
            .claim("rentalAccess", rentalAccess);
    return builder.build();
  }
}
