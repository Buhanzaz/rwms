package dev.buhanzaz.rwms.logistics.contractor.share;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders contractor-route failures as canonical Russian Problem Details without raw internals. */
@RestControllerAdvice
@RequiredArgsConstructor
public class ContractorRouteShareProblemHandler {
  private static final String PREFIX = "urn:rwms:problem:logistics:";
  private final RwmsProblemDetailFactory problems;

  /** Converts the bounded domain catalog to the shared Problem Details transport. */
  @ExceptionHandler(ContractorRouteShareProblem.class)
  public ResponseEntity<ApiProblem> routeShareProblem(
      ContractorRouteShareProblem problem, HttpServletRequest request) {
    URI type = URI.create(PREFIX + problem.code().toLowerCase(Locale.ROOT).replace('_', '-'));
    ApiProblem body =
        problems.create(
            type,
            problem.status().getReasonPhrase(),
            problem.status(),
            problem.getMessage(),
            URI.create(request.getRequestURI()),
            problem.code(),
            correlation(request));
    return ResponseEntity.status(problem.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private static CorrelationContext correlation(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    try {
      return new CorrelationContext(UUID.fromString(String.valueOf(value)), null);
    } catch (IllegalArgumentException exception) {
      return new CorrelationContext(UUID.randomUUID(), null);
    }
  }
}
