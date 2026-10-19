package dev.buhanzaz.rwms.dossier.eventing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;

/** Validates serialized sanitized cabin-activity and DLT payloads against their canonical outbound schemas. */
@Component
public final class DossierOutboundSchemaValidator {
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchema activity = load("dossier-cabin-activity-v1.schema.json");
  private final JsonSchema deadLetter = load("dossier-sanitized-dlt-v1.schema.json");

  public void activity(String canonicalEnvelope) {
    validate(activity, canonicalEnvelope);
  }

  public void deadLetter(String canonicalPayload) {
    validate(deadLetter, canonicalPayload);
  }

  private void validate(JsonSchema schema, String value) {
    try {
      if (!schema.validate(mapper.readTree(value)).isEmpty()) {
        throw new IllegalStateException("DOSSIER_OUTBOUND_SCHEMA_REJECTED");
      }
    } catch (IOException exception) {
      throw new IllegalStateException("DOSSIER_OUTBOUND_SCHEMA_REJECTED", exception);
    }
  }

  private JsonSchema load(String name) {
    String resource = "/contracts/events/dossier/" + name;
    try (InputStream input = DossierOutboundSchemaValidator.class.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("Dossier schema is missing: " + resource);
      return FACTORY.getSchema(mapper.readTree(input));
    } catch (IOException exception) {
      throw new IllegalStateException("Dossier schema cannot be loaded: " + resource, exception);
    }
  }
}
