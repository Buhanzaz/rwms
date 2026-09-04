package dev.buhanzaz.rwms.logistics.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/** Guards the browser headers required by exact contractor evidence upload. */
class ContractorRouteShareCorsTest {
  @Test
  void corsAllowsOnlyTheExistingHeadersPlusEvidenceDigestAndCaptureTime() {
    var source =
        new SecurityConfiguration().corsConfigurationSource(List.of("https://panel.example"));
    var request =
        new MockHttpServletRequest(
            "POST", "/api/logistics/public/v1/contractor-route-shares/token/evidence/id");
    var configuration = source.getCorsConfiguration(request);

    assertThat(configuration).isNotNull();
    assertThat(configuration.getAllowedHeaders())
        .containsExactly(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            "X-Correlation-Id",
            "Idempotency-Key",
            "X-Content-SHA256",
            "X-Captured-At");
    assertThat(configuration.getAllowedHeaders()).doesNotContain(HttpHeaders.CONTENT_LENGTH);
  }
}
