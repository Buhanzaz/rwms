package dev.buhanzaz.rwms.dossier.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.security.DossierAuthorizer;
import dev.buhanzaz.rwms.dossier.service.DossierCursorCodec;
import dev.buhanzaz.rwms.dossier.service.DossierQueryException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;

class DossierCursorAuthorizationQaTest {
  private static final UUID CABIN_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

  @Test
  void cursorIsOpaqueTamperDetectedCabinAndFilterBoundAndExpiresAtTheBoundary() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    DossierCursorCodec codec =
        new DossierCursorCodec(
            new ObjectMapper(), "stage-9-qa-cursor-secret-with-at-least-32-bytes", clock);
    DossierCursorCodec.CursorPosition position =
        new DossierCursorCodec.CursorPosition(
            null, NOW.minusSeconds(10), UUID.fromString("30000000-0000-0000-0000-000000000001"));

    String cursor = codec.encode(CABIN_ID, "filter-a", position);

    assertThat(codec.decode(cursor, CABIN_ID, "filter-a")).isEqualTo(position);
    assertInvalidCursor(codec, tamper(cursor), CABIN_ID, "filter-a");
    assertInvalidCursor(codec, cursor, UUID.randomUUID(), "filter-a");
    assertInvalidCursor(codec, cursor, CABIN_ID, "filter-b");
    assertInvalidCursor(codec, "x".repeat(4097), CABIN_ID, "filter-a");

    DossierCursorCodec expired =
        new DossierCursorCodec(
            new ObjectMapper(),
            "stage-9-qa-cursor-secret-with-at-least-32-bytes",
            Clock.fixed(NOW.plusSeconds(24 * 60 * 60), ZoneOffset.UTC));
    assertInvalidCursor(expired, cursor, CABIN_ID, "filter-a");
  }

  @Test
  void authorizerRequiresUserAndReadScopeAndFailsClosedOnMalformedWarehouseClaims() {
    DossierAuthorizer authorizer = new DossierAuthorizer(new MockEnvironment(), false);
    Jwt visible =
        jwt(
            "USER",
            "rwms.read",
            null,
            List.of(Map.of("warehouseId", WAREHOUSE_ID.toString(), "level", "VIEW")));

    assertThat(authorizer.requireReadScope(visible).warehouseIds()).containsExactly(WAREHOUSE_ID);
    assertThat(authorizer.requireReadScope(visible).unrestricted()).isFalse();

    Jwt malformed =
        jwt(
            "USER",
            "rwms.read",
            null,
            List.of(
                Map.of("warehouseId", "not-a-uuid", "level", "MANAGE"),
                Map.of("warehouseId", WAREHOUSE_ID.toString(), "level", "UNKNOWN")));
    assertThat(authorizer.requireReadScope(malformed).warehouseIds()).isEmpty();

    assertThatThrownBy(() -> authorizer.requireReadScope(jwt("WORKER", "rwms.read", null, List.of())))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> authorizer.requireReadScope(jwt("USER", "rwms.write", null, List.of())))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void systemAndWmsAdminsAreUnrestrictedButStillNeedUserReadEligibility() {
    DossierAuthorizer authorizer = new DossierAuthorizer(new MockEnvironment(), false);

    assertThat(
            authorizer
                .requireReadScope(jwt("USER", "rwms.read", "SYSTEM_ADMIN", List.of()))
                .unrestricted())
        .isTrue();
    assertThat(
            authorizer
                .requireReadScope(jwt("USER", "rwms.read", "WMS_ADMIN", List.of()))
                .unrestricted())
        .isTrue();
    assertThatThrownBy(
            () ->
                authorizer.requireReadScope(
                    jwt("SERVICE", "rwms.read", "SYSTEM_ADMIN", List.of())))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static void assertInvalidCursor(
      DossierCursorCodec codec, String cursor, UUID cabinId, String filterHash) {
    assertThatThrownBy(() -> codec.decode(cursor, cabinId, filterHash))
        .isInstanceOf(DossierQueryException.class)
        .extracting(exception -> ((DossierQueryException) exception).code())
        .isEqualTo("DOSSIER_INVALID_CURSOR");
  }

  private static String tamper(String cursor) {
    int index = cursor.length() - 1;
    char replacement = cursor.charAt(index) == 'A' ? 'B' : 'A';
    return cursor.substring(0, index) + replacement;
  }

  private static Jwt jwt(
      String principalType,
      String scope,
      String globalRole,
      List<Map<String, String>> warehouseAccess) {
    Jwt.Builder builder =
        Jwt.withTokenValue("qa-token")
            .header("alg", "none")
            .subject(UUID.randomUUID().toString())
            .issuedAt(NOW.minusSeconds(60))
            .expiresAt(NOW.plusSeconds(600))
            .claim("principal_type", principalType)
            .claim("scope", scope)
            .claim("warehouse_access", warehouseAccess);
    if (globalRole != null) builder.claim("global_role", globalRole);
    return builder.build();
  }
}
