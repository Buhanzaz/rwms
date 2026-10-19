package dev.buhanzaz.rwms.analytics.eventing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;

/** Loads and applies the canonical KPI event JSON schema before semantic envelope validation. */
@Component
public final class AnalyticsEventSchemaValidator {
  private static final String RESOURCE =
      "/contracts/events/analytics/group-kpi-day-v1.schema.json";
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchema schema = load();

  public void validate(byte[] raw) {
    try {
      if (!schema.validate(mapper.readTree(raw)).isEmpty()) {
        throw new AnalyticsValidationException("SOURCE_SCHEMA_REJECTED");
      }
    } catch (IOException exception) {
      throw new AnalyticsValidationException("SOURCE_SCHEMA_REJECTED", exception);
    }
  }

  private JsonSchema load() {
    try (InputStream input = AnalyticsEventSchemaValidator.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Analytics event schema is missing: " + RESOURCE);
      }
      return FACTORY.getSchema(mapper.readTree(input));
    } catch (IOException exception) {
      throw new IllegalStateException("Analytics event schema cannot be loaded: " + RESOURCE, exception);
    }
  }
}
