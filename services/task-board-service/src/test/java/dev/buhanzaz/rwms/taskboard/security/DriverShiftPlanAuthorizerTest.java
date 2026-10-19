package dev.buhanzaz.rwms.taskboard.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Verifies that the internal shift-plan boundary accepts only its exact service credential. */
class DriverShiftPlanAuthorizerTest {
  private final DriverShiftPlanAuthorizer authorizer = new DriverShiftPlanAuthorizer();

  @Test
  void onlyLogisticsServiceWithTheSinglePlannerScopeIsAccepted() {
    assertThatCode(
            () ->
                authorizer.requirePlanner(
                    jwt(
                        "SERVICE",
                        "logistics-service",
                        "logistics-service",
                        "task-board.driver-shifts.plan")))
        .doesNotThrowAnyException();

    for (Jwt invalid :
        new Jwt[] {
          jwt(
              "SERVICE",
              "logistics-service",
              "logistics-service",
              "task-board.driver-shifts.plan rwms.read"),
          jwt("SERVICE", "other-service", "logistics-service", "task-board.driver-shifts.plan"),
          jwt("SERVICE", "logistics-service", "other-service", "task-board.driver-shifts.plan"),
          jwt("USER", "logistics-service", "logistics-service", "task-board.driver-shifts.plan")
        }) {
      assertThatThrownBy(() -> authorizer.requirePlanner(invalid))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private Jwt jwt(String principalType, String clientId, String subject, String scope) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .subject(subject)
        .claim("principal_type", principalType)
        .claim("client_id", clientId)
        .claim("scope", scope)
        .build();
  }
}
