package dev.buhanzaz.rwms.dossier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DossierCursorCodecTest {
  private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
  private static final UUID CABIN = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID EVENT = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private final DossierCursorCodec codec = codec(NOW);

  @Test
  void roundTripsAndBindsCabinAndNormalizedFilter() {
    var position =
        new DossierCursorCodec.CursorPosition(
            Instant.parse("2026-07-18T10:00:00Z"),
            Instant.parse("2026-07-18T11:00:00Z"),
            EVENT);
    String token = codec.encode(CABIN, "a".repeat(64), position);

    assertThat(codec.decode(token, CABIN, "a".repeat(64))).isEqualTo(position);
    assertThatThrownBy(() -> codec.decode(token, UUID.randomUUID(), "a".repeat(64)))
        .isInstanceOf(DossierQueryException.class)
        .extracting("code")
        .isEqualTo("DOSSIER_INVALID_CURSOR");
    assertThatThrownBy(() -> codec.decode(token, CABIN, "b".repeat(64)))
        .isInstanceOf(DossierQueryException.class);
  }

  @Test
  void rejectsTamperOversizeAndExactExpiryBoundary() {
    String token =
        codec.encode(
            CABIN,
            "a".repeat(64),
            new DossierCursorCodec.CursorPosition(null, NOW, EVENT));
    assertThatThrownBy(
            () -> codec.decode(token.substring(0, token.length() - 1) + "x", CABIN, "a".repeat(64)))
        .isInstanceOf(DossierQueryException.class);
    assertThatThrownBy(() -> codec.decode("x".repeat(4097), CABIN, "a".repeat(64)))
        .isInstanceOf(DossierQueryException.class);
    assertThatThrownBy(
            () -> codec(NOW.plusSeconds(86_400)).decode(token, CABIN, "a".repeat(64)))
        .isInstanceOf(DossierQueryException.class);
  }

  private static DossierCursorCodec codec(Instant now) {
    return new DossierCursorCodec(
        JsonMapper.builder().findAndAddModules().build(),
        "a-strong-test-cursor-secret-with-at-least-32-bytes",
        Clock.fixed(now, ZoneOffset.UTC));
  }
}
