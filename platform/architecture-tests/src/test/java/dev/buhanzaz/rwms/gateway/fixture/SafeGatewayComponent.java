package dev.buhanzaz.rwms.gateway.fixture;

import org.springframework.stereotype.Component;

/** Safe stateless gateway fixture with one constructor-injected transport collaborator. */
@Component
public final class SafeGatewayComponent {
  private final RouteForwarder forwarder;

  public SafeGatewayComponent(RouteForwarder forwarder) {
    this.forwarder = forwarder;
  }

  public RouteForwarder forwarder() {
    return forwarder;
  }

  /** Gateway-local transport port that owns no domain or persistence state. */
  public interface RouteForwarder {}
}
