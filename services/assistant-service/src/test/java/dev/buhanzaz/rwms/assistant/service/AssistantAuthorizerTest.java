package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class AssistantAuthorizerTest {
  private final AssistantAuthorizer authorizer = new AssistantAuthorizer();

  @Test
  void grantsOnlyRentalEnabledBearerSubjects() {
    UUID subject = UUID.randomUUID();

    assertThat(authorizer.requireRentalUser(jwt(subject, true))).isEqualTo(subject);
    assertThat(authorizer.requireRentalUser(jwt(subject, "true"))).isEqualTo(subject);
  }

  @Test
  void rejectsBearerWithoutRentalAccessOrUuidSubject() {
    assertThatThrownBy(() -> authorizer.requireRentalUser(jwt(UUID.randomUUID(), false)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireRentalUser(jwt("not-a-uuid", true)))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static Jwt jwt(UUID subject, Object rentalAccess) {
    return jwt(subject.toString(), rentalAccess);
  }

  private static Jwt jwt(String subject, Object rentalAccess) {
    Instant now = Instant.now();
    return new Jwt(
        "test-token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", subject, "rentalAccess", rentalAccess));
  }
}
