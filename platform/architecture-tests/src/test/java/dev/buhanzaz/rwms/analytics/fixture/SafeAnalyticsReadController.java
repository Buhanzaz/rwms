package dev.buhanzaz.rwms.analytics.fixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Safe read-model fixture with constructor injection and a query-only HTTP mapping. */
@RestController
@RequestMapping("/architecture-fixture")
public final class SafeAnalyticsReadController {
  private final AnalyticsReader reader;

  public SafeAnalyticsReadController(AnalyticsReader reader) {
    this.reader = reader;
  }

  @GetMapping("/analytics")
  public String read() {
    return reader.read();
  }

  @RequestMapping(path = "/analytics-generic", method = RequestMethod.GET)
  public String readWithGenericMapping() {
    return reader.read();
  }

  /** Service-owned query port used by the safe analytics fixture. */
  public interface AnalyticsReader {
    String read();
  }
}
