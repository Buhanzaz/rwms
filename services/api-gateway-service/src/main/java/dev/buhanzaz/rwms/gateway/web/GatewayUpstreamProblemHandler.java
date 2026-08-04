package dev.buhanzaz.rwms.gateway.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
@RequiredArgsConstructor
public class GatewayUpstreamProblemHandler {

  private final GatewayUpstreamProblemWriter problems;

  public boolean supports(Throwable error) {
    return hasCause(error, ResourceAccessException.class);
  }

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
