package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Canonical JSON codec for the durable logistics-owned WorkerApp content snapshot. */
@Component
public class DriverTaskWorkerContentCodec {
  private final ObjectMapper json;

  /** Creates the codec with the service-wide strict Jackson configuration. */
  public DriverTaskWorkerContentCodec(ObjectMapper json) {
    this.json = json;
  }

  /** Serializes one already validated immutable content snapshot. */
  public String encode(DriverTaskWorkerContent content) {
    try {
      return json.writeValueAsString(content == null ? DriverTaskWorkerContent.empty() : content);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Driver task worker content could not be serialized", exception);
    }
  }

  /** Decodes a persisted snapshot; old rows without structured content remain compatible. */
  public DriverTaskWorkerContent decode(String value) {
    try {
      return json.readValue(
          value == null || value.isBlank() ? "{}" : value, DriverTaskWorkerContent.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored driver task worker content is invalid", exception);
    }
  }
}
