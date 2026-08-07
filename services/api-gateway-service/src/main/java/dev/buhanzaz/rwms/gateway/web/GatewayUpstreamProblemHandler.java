package dev.buhanzaz.rwms.gateway.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Classifies synchronous gateway proxy transport failures before they reach callers.
 *
 * <p>Only connectivity failures are handled here. Downstream HTTP responses remain transparent
 * to clients, preserving the owning service's Problem Details semantics.
 */
@Component
@RequiredArgsConstructor
public class GatewayUpstreamProblemHandler {

  private final GatewayUpstreamProblemWriter problems;

  /**
   * Returns whether the exception chain contains a synchronous upstream access failure.
   *
   * @param error proxy error to inspect
   * @return {@code true} when the error can be safely mapped to a gateway transport problem
   */
  public boolean supports(Throwable error) {
    return hasCause(error, ResourceAccessException.class);
  }

  /**
   * Maps a supported transport failure to the public gateway Problem Details response.
   *
   * @param error classified upstream access failure
   * @param request failed gateway request
   * @return sanitized error response
   */
  public ServerResponse handle(Throwable error, ServerRequest request) {
    return this.problems.response(error, request);
  }

  private static <T extends Throwable> boolean hasCause(Throwable error, Class<T> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
    }
    return false;
  }
}
