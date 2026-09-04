package dev.buhanzaz.rwms.logistics.contractor.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies contractor capability domain separation, revision binding and constant-shape rejection.
 */
class ContractorRouteShareTokenServiceTest {
  private static final String SECRET = "contractor-route-share-test-secret-32-characters";

  @Test
  void roundTripsOnlyTheExactShareAndRevision() {
    ContractorRouteShareTokenService tokens = tokens();
    UUID shareId = UUID.randomUUID();

    String token = tokens.issue(shareId, 7);

    assertThat(tokens.verify(token))
        .isEqualTo(new ContractorRouteShareTokenService.TokenIdentity(shareId, 7));
    assertThat(token).doesNotContain(shareId.toString());
  }

  @Test
  void rejectsTamperingWrongRevisionShapeAndAnotherSecret() {
    ContractorRouteShareTokenService tokens = tokens();
    String token = tokens.issue(UUID.randomUUID(), 1);

    assertThatThrownBy(() -> tokens.verify(token + "0"))
        .isInstanceOf(
            ContractorRouteShareTokenService.InvalidContractorRouteShareTokenException.class);
    assertThatThrownBy(() -> tokens.verify("not-a-capability"))
        .isInstanceOf(
            ContractorRouteShareTokenService.InvalidContractorRouteShareTokenException.class);
    assertThatThrownBy(
            () ->
                new ContractorRouteShareTokenService(
                        new MockEnvironment(), "another-contractor-route-secret-32-characters")
                    .verify(token))
        .isInstanceOf(
            ContractorRouteShareTokenService.InvalidContractorRouteShareTokenException.class);
  }

  @Test
  void refusesWeakOrKnownProductionSecrets() {
    assertThatThrownBy(() -> new ContractorRouteShareTokenService(new MockEnvironment(), "short"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new ContractorRouteShareTokenService(
                    new MockEnvironment().withProperty("spring.profiles.active", "production"),
                    "rwms-local-client-presentation-secret-change-me"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ContractorRouteShareTokenService tokens() {
    return new ContractorRouteShareTokenService(new MockEnvironment(), SECRET);
  }
}
