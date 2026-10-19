package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ActorSnapshot;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.ActorType;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceActorReferenceProvider;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Converts current and persisted disposition actor snapshots without changing their wire shape. */
@Service
final class PropertyDispositionActorCodec {
  private static final String SYSTEM_ACTOR_ID = "00000000-0000-0000-0000-0000000000d6";

  private final MaintenanceActorReferenceProvider actors;
  private final ObjectMapper mapper;

  PropertyDispositionActorCodec(MaintenanceActorReferenceProvider actors, ObjectMapper mapper) {
    this.actors = actors;
    this.mapper = mapper;
  }

  String actorJson() {
    var actor = actors.current();
    return actor == null ? systemActorJson() : write(actor);
  }

  String systemActorJson() {
    return write(
        Map.of(
            "subjectId", SYSTEM_ACTOR_ID,
            "principalType", "SYSTEM",
            "profileRevision", SYSTEM_ACTOR_ID));
  }

  ActorSnapshot actor(String value) {
    ActorSnapshot actor = actorOrNull(value);
    if (actor == null) {
      throw new IllegalStateException("Property disposition actor snapshot is invalid");
    }
    return actor;
  }

  ActorSnapshot actorOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      JsonNode node = mapper.readTree(value);
      String id = node.path("subjectId").stringValue();
      if (id == null || id.isBlank()) {
        id = node.path("actorId").stringValue();
      }
      String type = node.path("principalType").stringValue();
      if (id == null || id.isBlank() || type == null || type.isBlank()) {
        return null;
      }
      return new ActorSnapshot(id, "USER".equals(type) ? ActorType.USER : ActorType.SERVICE);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Property disposition value cannot be serialized", exception);
    }
  }
}
