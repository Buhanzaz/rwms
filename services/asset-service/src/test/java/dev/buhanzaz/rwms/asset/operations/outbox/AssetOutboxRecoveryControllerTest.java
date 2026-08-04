package dev.buhanzaz.rwms.asset.operations.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class AssetOutboxRecoveryControllerTest {
  private final AssetOutboxRecoveryService recovery = mock(AssetOutboxRecoveryService.class);
  private final AssetOutboxRecoveryController controller = new AssetOutboxRecoveryController(
      recovery, new AssetAuthorizer(new MockEnvironment(), false));

  @Test
  void routesAnAdministratorReviewWithTheAuthenticatedReviewerSubject() {
    UUID eventId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    AssetOutboxRequeueResponse expected = new AssetOutboxRequeueResponse(
        eventId, 1, "PENDING", 0, null, Instant.parse("2026-08-05T11:00:00Z"));
    when(recovery.requeue(eventId, 0L, subjectId, "  verified  ")).thenReturn(expected);

    AssetOutboxRequeueResponse response = controller.requeue(
        user(subjectId, "WMS_ADMIN", "rwms.read rwms.write"),
        eventId,
        new AssetOutboxRequeueRequest(0L, "  verified  "));

    assertThat(response).isEqualTo(expected);
    verify(recovery).requeue(eventId, 0L, subjectId, "  verified  ");
  }

  @Test
  void rejectsWarehouseManagersBeforeTheRecoveryCommandRuns() {
    assertThatThrownBy(() -> controller.requeue(
        user(UUID.randomUUID(), "WAREHOUSE_MANAGER", "rwms.read rwms.write"),
        UUID.randomUUID(),
        new AssetOutboxRequeueRequest(0L, "review")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("administrator");

    verifyNoInteractions(recovery);
  }

  private static Jwt user(UUID subjectId, String role, String scope) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub", subjectId.toString(),
            "principal_type", "USER",
            "scope", scope,
            "global_role", role));
  }
}
