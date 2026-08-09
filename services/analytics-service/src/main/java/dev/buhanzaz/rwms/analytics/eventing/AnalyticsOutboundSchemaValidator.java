package dev.buhanzaz.rwms.analytics.eventing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;

/** Validates the sanitized analytics DLT payload before it may leave the service-owned outbox. */
@Component
public final class AnalyticsOutboundSchemaValidator {
  private static final String RESOURCE =
      "/contracts/events/analytics/analytics-sanitized-dlt-v1.schema.json";
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchema schema = load();

  public void deadLetter(String canonicalPayload) {
    try {
      if (!schema.validate(mapper.readTree(canonicalPayload)).isEmpty()) {
        throw new IllegalStateException("ANALYTICS_OUTBOUND_SCHEMA_REJECTED");
      }
    } catch (IOException exception) {
      throw new IllegalStateException("ANALYTICS_OUTBOUND_SCHEMA_REJECTED", exception);
    }
  }

  private JsonSchema load() {
    try (InputStream input = AnalyticsOutboundSchemaValidator.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Analytics DLT schema is missing: " + RESOURCE);
      }
      return FACTORY.getSchema(mapper.readTree(input));
    } catch (IOException exception) {
      throw new IllegalStateException("Analytics DLT schema cannot be loaded: " + RESOURCE, exception);
    }
  }
}
