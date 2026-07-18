package dev.buhanzaz.rwms.dossier.eventing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Runtime validation against the immutable producer-owned canonical JSON Schemas. */
@Component
public final class DossierProducerSchemaValidator {
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, JsonSchema> schemas;

  public DossierProducerSchemaValidator() {
    JsonSchema asset = load("asset/asset-events-v1.schema.json");
    JsonSchema maintenance = load("maintenance/maintenance-events-v1.schema.json");
    JsonSchema inventory = load("inventory/inventory-events-v1.schema.json");
    JsonSchema media = load("media/media-facts-v1.schema.json");
    JsonSchema logistics = load("logistics/logistics-events-v1.schema.json");
    JsonSchema taskBoard = load("task-board/task-board-events-v1.schema.json");
    schemas =
        Map.ofEntries(
            Map.entry("rwms.asset.rental-item.v1", asset),
            Map.entry("rwms.maintenance.estimate.v1", maintenance),
            Map.entry("rwms.maintenance.repair.v1", maintenance),
            Map.entry("rwms.inventory.session.v1", inventory),
            Map.entry("rwms.inventory.publication.v1", inventory),
            Map.entry("rwms.media.media.v1", media),
            Map.entry("rwms.logistics.return.v1", logistics),
            Map.entry("rwms.logistics.shipment.v1", logistics),
            Map.entry("rwms.logistics.transfer.v1", logistics),
            Map.entry("rwms.task-board.board-task.v1", taskBoard),
            Map.entry("rwms.task-board.queue-entry.v1", taskBoard));
  }

  public void validate(String topic, byte[] raw) {
    JsonSchema schema = schemas.get(topic);
    if (schema == null) throw new DossierValidationException("SOURCE_TOPIC_REJECTED");
    try {
      if (!schema.validate(mapper.readTree(raw)).isEmpty()) {
        throw new DossierValidationException("SOURCE_SCHEMA_REJECTED");
      }
    } catch (IOException exception) {
      throw new DossierValidationException("SOURCE_SCHEMA_REJECTED", exception);
    }
  }

  private JsonSchema load(String relativePath) {
    String resource = "/contracts/events/" + relativePath;
    try (InputStream input = DossierProducerSchemaValidator.class.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalStateException("Canonical producer schema is missing: " + resource);
      return FACTORY.getSchema(mapper.readTree(input));
    } catch (IOException exception) {
      throw new IllegalStateException("Canonical producer schema cannot be loaded: " + resource, exception);
    }
  }
}
