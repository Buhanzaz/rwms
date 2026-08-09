package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.ClassifierRequest;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.ClassifierResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateClassifierRequest;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Owns classifier persistence, optimistic fencing and classifier event facts.
 *
 * <p>The service has no rental, equipment, lease or hold workflow knowledge; callers retain the
 * transaction boundary while this collaborator changes the classifier aggregate only.
 */
@Service
final class AssetClassifierService {
  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final AssetIdempotencyStore idempotency;
  private final AssetJsonCodec json;

  AssetClassifierService(
      JdbcTemplate jdbc,
      AssetEventStore events,
      AssetIdempotencyStore idempotency,
      AssetJsonCodec json) {
    this.jdbc = jdbc;
    this.events = events;
    this.idempotency = idempotency;
    this.json = json;
  }

  List<ClassifierResponse> classifiers(String type) {
    return jdbc.query(
        "select id,version,classifier_type,parent_id,name,active,sort_order from asset_classifier where (? is null or classifier_type=?) order by classifier_type,sort_order nulls last,name,id",
        (rs, row) ->
            new ClassifierResponse(
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                rs.getString("classifier_type"),
                rs.getObject("parent_id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("active"),
                rs.getObject("sort_order", Integer.class)),
        type,
        type);
  }

  AssetService.CreateResult<ClassifierResponse> create(
      UUID subjectId, UUID key, CreateClassifierRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "classifier.create", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), ClassifierResponse.class), true);
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into asset_classifier(id,version,classifier_type,parent_id,name,active,sort_order,created_at,updated_at) values (?,0,?,?,?,?,?,clock_timestamp(),clock_timestamp())",
        id,
        classifierType(request.type()),
        request.parentId(),
        required(request.name(), 255),
        request.active(),
        request.sortOrder());
    ClassifierResponse response = classifier(id);
    events.initialize(
        AssetAggregateType.CLASSIFIER,
        id,
        0,
        AssetEventType.CLASSIFIER_CREATED,
        classifierFact(response),
        classifierSnapshot(response));
    idempotency.store(subjectId, "classifier.create", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  ClassifierResponse update(UUID id, ClassifierRequest request) {
    ClassifierResponse current = classifier(id);
    assertVersion(current.version(), request.expectedVersion());
    int changed =
        jdbc.update(
            """
            update asset_classifier set classifier_type=?,parent_id=?,name=?,active=?,sort_order=?,version=version+1,updated_at=clock_timestamp()
            where id=? and version=?
            """,
            classifierType(request.type()),
            request.parentId(),
            required(request.name(), 255),
            request.active(),
            request.sortOrder(),
            id,
            request.expectedVersion());
    if (changed != 1) {
      throw new AssetConflictException("Classifier changed concurrently");
    }
    ClassifierResponse updated = classifier(id);
    events.append(
        AssetAggregateType.CLASSIFIER,
        id,
        request.expectedVersion(),
        AssetEventType.CLASSIFIER_CHANGED,
        classifierFact(updated),
        classifierSnapshot(updated));
    return updated;
  }

  private ClassifierResponse classifier(UUID id) {
    return jdbc.query(
            "select id,version,classifier_type,parent_id,name,active,sort_order from asset_classifier where id=?",
            (rs, row) ->
                new ClassifierResponse(
                    rs.getObject("id", UUID.class),
                    rs.getLong("version"),
                    rs.getString("classifier_type"),
                    rs.getObject("parent_id", UUID.class),
                    rs.getString("name"),
                    rs.getBoolean("active"),
                    rs.getObject("sort_order", Integer.class)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Classifier was not found"));
  }

  private static String classifierType(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!List.of("CATEGORY", "SUBCATEGORY", "TYPE", "CONDITION").contains(normalized)) {
      throw new IllegalArgumentException("Unsupported classifier type");
    }
    return normalized;
  }

  private static String required(String value, int max) {
    if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
      throw new IllegalArgumentException("Value is required and bounded");
    }
    return value.trim();
  }

  private static void assertVersion(long actual, Long expected) {
    if (expected == null || expected < 0) {
      throw new IllegalArgumentException("expectedVersion is required");
    }
    if (actual != expected) {
      throw new AssetConflictException("Asset data changed concurrently");
    }
  }

  private static Map<String, ?> classifierFact(ClassifierResponse value) {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("classifierId", value.id().toString());
    fact.put("type", value.type());
    fact.put("parentId", value.parentId() == null ? null : value.parentId().toString());
    fact.put("label", value.name());
    fact.put("active", value.active());
    fact.put("sortOrder", value.sortOrder());
    return fact;
  }

  private static Map<String, ?> classifierSnapshot(ClassifierResponse value) {
    return classifierFact(value);
  }
}
