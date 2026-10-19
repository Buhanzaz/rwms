package dev.buhanzaz.rwms.logistics.contractor.share;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Rejects malformed or oversized anonymous contractor evidence before Spring MVC binds its binary
 * request body. Direct requests use their canonical content length; a chunked internal gateway
 * relay uses the gateway-overwritten private length fact. The filter is deliberately restricted to
 * the exact public evidence route shape; checksum and actual-body-length validation remain in the
 * route-share application service.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public final class ContractorEvidenceIngressFilter extends OncePerRequestFilter {
  private static final Pattern EVIDENCE_POST_PATH =
      Pattern.compile(
          "^/api/logistics/public/v1/contractor-route-shares/[^/]+/tasks/[^/]+/entries/[^/]+/evidence/[^/]+$");
  private static final MediaType WEBP = MediaType.parseMediaType("image/webp");
  private static final long MAXIMUM_JPEG_BYTES = 15_728_640;
  private static final long MAXIMUM_WEBP_BYTES = 1_048_576;
  private static final String GATEWAY_DECLARED_LENGTH = "X-RWMS-Contractor-Evidence-Length";
  private static final String PROBLEM_PREFIX = "urn:rwms:problem:logistics:";

  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  /** Creates the pre-dispatch boundary with the shared canonical Problem Details mapper. */
  public ContractorEvidenceIngressFilter(
      ObjectMapper objectMapper, RwmsProblemDetailFactory problems) {
    this.objectMapper = objectMapper;
    this.problems = problems;
  }

  /** Applies ingress checks only to the one anonymous binary command owned by this boundary. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !EVIDENCE_POST_PATH.matcher(pathWithinApplication(request)).matches();
  }

  /**
   * Rejects unsafe representation metadata before delegating to any body-binding filter/servlet.
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    MediaType contentType = contentType(request);
    if (contentType == null) {
      reject(
          request,
          response,
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "CONTRACTOR_ROUTE_EVIDENCE_MEDIA_UNSUPPORTED",
          "Поддерживаются только фотографии JPEG и WebP");
      return;
    }
    Long contentLength = declaredContentLength(request);
    if (contentLength == null) {
      reject(
          request,
          response,
          HttpStatus.LENGTH_REQUIRED,
          "CONTRACTOR_ROUTE_EVIDENCE_LENGTH_REQUIRED",
          "Для фотографии необходимо передать подтверждённый точный размер");
      return;
    }
    long maximum =
        MediaType.IMAGE_JPEG.equals(contentType) ? MAXIMUM_JPEG_BYTES : MAXIMUM_WEBP_BYTES;
    if (contentLength > maximum) {
      reject(
          request,
          response,
          HttpStatus.CONTENT_TOO_LARGE,
          "CONTRACTOR_ROUTE_EVIDENCE_SIZE_INVALID",
          "Размер фотографии превышает допустимый предел для выбранного формата");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static MediaType contentType(HttpServletRequest request) {
    List<String> values = Collections.list(request.getHeaders(HttpHeaders.CONTENT_TYPE));
    if (values.size() != 1) return null;
    try {
      MediaType value = MediaType.parseMediaType(values.getFirst());
      return MediaType.IMAGE_JPEG.equals(value) || WEBP.equals(value) ? value : null;
    } catch (InvalidMediaTypeException exception) {
      return null;
    }
  }

  private static Long declaredContentLength(HttpServletRequest request) {
    List<String> transferEncodings =
        Collections.list(request.getHeaders(HttpHeaders.TRANSFER_ENCODING));
    List<String> contentLengths = Collections.list(request.getHeaders(HttpHeaders.CONTENT_LENGTH));
    List<String> gatewayLengths = Collections.list(request.getHeaders(GATEWAY_DECLARED_LENGTH));
    if (transferEncodings.isEmpty()) {
      if (contentLengths.size() != 1 || !gatewayLengths.isEmpty()) return null;
      return parseLength(contentLengths.getFirst());
    }
    if (transferEncodings.size() != 1
        || !"chunked".equalsIgnoreCase(transferEncodings.getFirst())
        || gatewayLengths.size() != 1
        || contentLengths.size() > 1) {
      return null;
    }
    Long gatewayLength = parseLength(gatewayLengths.getFirst());
    if (gatewayLength == null) return null;
    if (contentLengths.isEmpty()) return gatewayLength;
    Long contentLength = parseLength(contentLengths.getFirst());
    return gatewayLength.equals(contentLength) ? gatewayLength : null;
  }

  private static Long parseLength(String value) {
    if (value == null || !value.matches("(?:0|[1-9][0-9]*)")) return null;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private void reject(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    UUID correlationId = correlation(request, response);
    ApiProblem problem =
        problems.create(
            URI.create(PROBLEM_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-')),
            status.getReasonPhrase(),
            status,
            detail,
            URI.create(request.getRequestURI()),
            code,
            new CorrelationContext(correlationId, null));
    response.resetBuffer();
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    objectMapper.writeValue(response.getOutputStream(), problem);
  }

  private static UUID correlation(HttpServletRequest request, HttpServletResponse response) {
    Object existing = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    UUID value;
    try {
      value = UUID.fromString(String.valueOf(existing));
    } catch (IllegalArgumentException exception) {
      value = UUID.randomUUID();
      request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, value.toString());
    }
    response.setHeader(CorrelationIdFilter.HEADER_NAME, value.toString());
    return value;
  }

  private static String pathWithinApplication(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
  }
}
