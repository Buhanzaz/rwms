package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.eventing.InventoryEventChecksum;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

@Service
public class InventoryCanonicalJsonService implements InventoryCanonicalJsonPort {
  private final ObjectMapper mapper;

  public InventoryCanonicalJsonService(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String sha256(Object value) {
    try {
      String canonical =
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(value);
      return InventoryEventChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory value cannot be canonicalized", exception);
    }
  }
}
