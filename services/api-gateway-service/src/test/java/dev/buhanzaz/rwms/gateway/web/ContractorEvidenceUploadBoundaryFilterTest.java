package dev.buhanzaz.rwms.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/** Verifies the pre-proxy byte boundary for anonymous contractor evidence. */
class ContractorEvidenceUploadBoundaryFilterTest {

  private static final String PATH =
      "/api/logistics/public/v1/contractor-route-shares/token/tasks/task-id/entries/entry-id/evidence/evidence-id";
  private final ContractorEvidenceUploadBoundaryFilter filter =
      new ContractorEvidenceUploadBoundaryFilter(
          new ObjectMapper(), new RwmsProblemDetailFactory());

  /** A bounded supported body reaches the transport proxy without being consumed by the filter. */
  @Test
  void allowsKnownBoundedEvidenceWithoutReadingItsBody() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
    request.setContentType("image/jpeg");
    byte[] body = "unchanged-body".getBytes(StandardCharsets.UTF_8);
    request.setContent(body);
    request.addHeader(ContractorEvidenceUploadBoundaryFilter.DECLARED_LENGTH_HEADER, "999999");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean invoked = new AtomicBoolean();
    FilterChain chain =
        (acceptedRequest, ignored) -> {
          invoked.set(true);
          HttpServletRequest acceptedHttpRequest = (HttpServletRequest) acceptedRequest;
          assertThat(
                  acceptedHttpRequest.getHeader(
                      ContractorEvidenceUploadBoundaryFilter.DECLARED_LENGTH_HEADER))
              .isEqualTo(Integer.toString(body.length));
          assertThat(
                  Collections.list(
                      acceptedHttpRequest.getHeaders(
                          ContractorEvidenceUploadBoundaryFilter.DECLARED_LENGTH_HEADER)))
              .containsExactly(Integer.toString(body.length));
          assertThat(acceptedRequest.getInputStream().readAllBytes()).isEqualTo(body);
        };

    filter.doFilter(request, response, chain);

    assertThat(invoked).isTrue();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  /** Oversized, unknown-length and unsupported uploads stop before the proxy chain. */
  @Test
  void rejectsInvalidIngressBeforeProxying() throws Exception {
    assertRejected(evidence("image/webp", 1_048_577), HttpStatus.CONTENT_TOO_LARGE);
    assertRejected(evidence("image/jpeg", -1), HttpStatus.LENGTH_REQUIRED);
    assertRejected(evidence("image/png", 100), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertRejected(evidence("image/*", 100), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  /** Unrelated requests remain outside this narrow anonymous-upload boundary. */
  @Test
  void ignoresEveryOtherGatewayRouteAndMethod() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean invoked = new AtomicBoolean();

    filter.doFilter(request, response, (accepted, ignored) -> invoked.set(true));

    assertThat(invoked).isTrue();
  }

  private void assertRejected(MockHttpServletRequest request, HttpStatus status) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean invoked = new AtomicBoolean();

    filter.doFilter(request, response, (accepted, ignored) -> invoked.set(true));

    assertThat(invoked).isFalse();
    assertThat(response.getStatus()).isEqualTo(status.value());
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getContentAsString()).contains("GATEWAY_CONTRACTOR_EVIDENCE_");
  }

  private static MockHttpServletRequest evidence(String contentType, int contentLength) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
    request.setContentType(contentType);
    if (contentLength >= 0) request.setContent(new byte[contentLength]);
    return request;
  }
}
