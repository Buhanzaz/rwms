package dev.buhanzaz.rwms.gateway.web;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Rejects an invalid anonymous contractor-evidence body before the gateway proxy reads it.
 *
 * <p>The exact capability path accepts only a known-length JPEG up to 15 MiB or WebP up to 1 MiB.
 * The caller cannot choose the private downstream length assertion: this filter overwrites it with
 * the validated public request length. The owning logistics service repeats the boundary and
 * validates the actual bytes and checksum; this edge check protects gateway memory and is not a
 * business authorization decision.
 */
public final class ContractorEvidenceUploadBoundaryFilter extends OncePerRequestFilter {

  static final String DECLARED_LENGTH_HEADER = "X-RWMS-Contractor-Evidence-Length";
  private static final Pattern EVIDENCE_PATH =
      Pattern.compile(
          "^/api/logistics/public/v1/contractor-route-shares/[^/]+/tasks/[^/]+/entries/[^/]+/evidence/[^/]+$");
  private static final long JPEG_MAX_BYTES = 15_728_640L;
  private static final long WEBP_MAX_BYTES = 1_048_576L;
  private static final String WEBP = "image/webp";

  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  /** Creates the transport boundary with the shared public Problem Details writer. */
  public ContractorEvidenceUploadBoundaryFilter(
      ObjectMapper objectMapper, RwmsProblemDetailFactory problems) {
    this.objectMapper = objectMapper;
    this.problems = problems;
  }

  /** Validates media type and declared byte length without consuming the servlet input stream. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String contentType = canonicalContentType(request.getContentType());
    if (contentType == null) {
      reject(
          request,
          response,
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "GATEWAY_CONTRACTOR_EVIDENCE_MEDIA_UNSUPPORTED",
          "Поддерживаются только фотографии JPEG и WebP");
      return;
    }
    long declaredLength = request.getContentLengthLong();
    if (declaredLength < 0) {
      reject(
          request,
          response,
          HttpStatus.LENGTH_REQUIRED,
          "GATEWAY_CONTRACTOR_EVIDENCE_LENGTH_REQUIRED",
          "Для фотографии необходимо указать размер файла");
      return;
    }
    long maximum = WEBP.equals(contentType) ? WEBP_MAX_BYTES : JPEG_MAX_BYTES;
    if (declaredLength == 0 || declaredLength > maximum) {
      reject(
          request,
          response,
          declaredLength == 0 ? HttpStatus.BAD_REQUEST : HttpStatus.CONTENT_TOO_LARGE,
          "GATEWAY_CONTRACTOR_EVIDENCE_SIZE_INVALID",
          "Размер фотографии недопустим для выбранного формата");
      return;
    }
    filterChain.doFilter(withCanonicalLength(request, declaredLength), response);
  }

  /** Applies the boundary only to the exact anonymous binary evidence command. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !HttpMethod.POST.name().equals(request.getMethod())
        || !EVIDENCE_PATH.matcher(request.getRequestURI()).matches();
  }

  private static String canonicalContentType(String value) {
    if (value == null) return null;
    try {
      MediaType type = MediaType.parseMediaType(value);
      String essence = type.getType() + "/" + type.getSubtype();
      if (MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(essence)) {
        return MediaType.IMAGE_JPEG_VALUE;
      }
      if (WEBP.equalsIgnoreCase(essence)) return WEBP;
      return null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  /** Replaces any caller value with the edge-validated length used by the chunked proxy hop. */
  private static HttpServletRequest withCanonicalLength(
      HttpServletRequest request, long declaredLength) {
    String canonicalLength = Long.toString(declaredLength);
    return new HttpServletRequestWrapper(request) {
      @Override
      public String getHeader(String name) {
        return isDeclaredLengthHeader(name) ? canonicalLength : super.getHeader(name);
      }

      @Override
      public Enumeration<String> getHeaders(String name) {
        return isDeclaredLengthHeader(name)
            ? Collections.enumeration(Collections.singleton(canonicalLength))
            : super.getHeaders(name);
      }

      @Override
      public Enumeration<String> getHeaderNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
        names.removeIf(ContractorEvidenceUploadBoundaryFilter::isDeclaredLengthHeader);
        names.add(DECLARED_LENGTH_HEADER);
        return Collections.enumeration(names);
      }
    };
  }

  private static boolean isDeclaredLengthHeader(String name) {
    return DECLARED_LENGTH_HEADER.equalsIgnoreCase(name);
  }

  private void reject(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    UUID correlationId = correlation(request);
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId.toString());
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        problems.create(
            URI.create(
                "urn:rwms:problem:gateway:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
            status.getReasonPhrase(),
            status,
            detail,
            URI.create(request.getRequestURI()),
            code,
            new CorrelationContext(correlationId, null)));
  }

  private static UUID correlation(HttpServletRequest request) {
    Object attribute = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    String candidate =
        attribute == null
            ? request.getHeader(CorrelationIdFilter.HEADER_NAME)
            : attribute.toString();
    try {
      return UUID.fromString(candidate);
    } catch (IllegalArgumentException | NullPointerException exception) {
      return UUID.randomUUID();
    }
  }
}
