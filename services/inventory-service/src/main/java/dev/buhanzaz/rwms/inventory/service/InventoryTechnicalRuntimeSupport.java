package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.eventing.InventoryEventChecksum;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Technical runtime capability base for inventory workflow collaborators.
 *
 * <p>It deliberately exposes no repository or integration dependency. Narrow use-case supports
 * own their exact persistence surface, while this base retains only JSON, actor and transaction
 * operations needed to preserve shared serialization and transaction semantics.
 */
abstract class InventoryTechnicalRuntimeSupport {
  protected static final Logger log = LoggerFactory.getLogger(InventoryTechnicalRuntimeSupport.class);

  private final ObjectMapper objectMapper;
  private final InventoryCanonicalJsonPort canonicalJson;
  private final InventoryAuthorizer inventoryAuthorizer;
  private final PlatformTransactionManager transactionManager;
  /** Narrow JSON capability; the raw mapper is intentionally private. */
  protected final JsonOperations mapper;
  /** Narrow authorization capability; the raw authorizer is intentionally private. */
  protected final AuthorizationOperations authorizer;
  /** Transaction boundary capability shared by a use case, not a persistence dependency. */
  protected final TransactionTemplate transactions;
  /** Isolated recovery transaction capability, preserving the original requires-new semantics. */
  protected final TransactionTemplate independentTransactions;

  protected InventoryTechnicalRuntimeSupport(
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    objectMapper = mapper;
    this.canonicalJson = canonicalJson;
    inventoryAuthorizer = authorizer;
    this.transactionManager = transactionManager;
    this.mapper = new JsonOperations(objectMapper);
    this.authorizer = new AuthorizationOperations(inventoryAuthorizer);
    transactions = new TransactionTemplate(this.transactionManager);
    independentTransactions = new TransactionTemplate(this.transactionManager);
    independentTransactions.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  protected final ObjectNode objectNode() {
    return objectMapper.createObjectNode();
  }

  protected final ArrayNode arrayNode() {
    return objectMapper.createArrayNode();
  }

  protected final JsonNode valueTree(Object value) {
    return objectMapper.valueToTree(value);
  }

  protected UUID requiredUuid(JsonNode value, String field, String name) {
    try {
      return UUID.fromString(requiredText(value, field, name));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Persisted " + name + " is invalid", exception);
    }
  }

  protected boolean requiredBoolean(JsonNode value, String field, String name) {
    JsonNode result = value.get(field);
    if (result == null || !result.isBoolean()) {
      throw new IllegalStateException("Persisted " + name + " is missing");
    }
    return result.booleanValue();
  }

  protected String requiredText(JsonNode value, String field, String name) {
    String result = value.path(field).asText();
    if (result.isBlank()) {
      throw new IllegalStateException("Persisted " + name + " is missing");
    }
    return result;
  }

  protected String nullableText(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  protected JsonNode boundedSafeSnapshot(String value, boolean arrayAllowed, String name) {
    if (value == null || value.length() > 65_536) {
      throw new IllegalStateException("Persisted " + name + " snapshot exceeds the safe bound");
    }
    JsonNode parsed;
    try {
      parsed = read(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Persisted " + name + " snapshot is invalid", exception);
    }
    if (!parsed.isObject() && !(arrayAllowed && parsed.isArray())) {
      throw new IllegalStateException("Persisted " + name + " snapshot has an unsafe shape");
    }
    return parsed;
  }

  protected OpaqueActorReference actor(Jwt jwt) {
    return new OpaqueActorReference(
        authorizer.subjectId(jwt).toString(), "USER", authorizer.profileRevision(jwt));
  }

  protected String actorJson(Jwt jwt) {
    return write(actor(jwt));
  }

  protected UUID correlationId() {
    String value = MDC.get("correlationId");
    try {
      return value == null ? UUID.randomUUID() : UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      return UUID.randomUUID();
    }
  }

  protected String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory value is not serializable", exception);
    }
  }

  protected String canonicalWrite(Object value) {
    try {
      return objectMapper
          .writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory value is not serializable", exception);
    }
  }

  protected JsonNode read(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Stored inventory JSON is invalid", exception);
    }
  }

  protected boolean storedJsonEquals(String stored, JsonNode current) {
    if (stored == null || current == null || current.isNull()) {
      return stored == null && (current == null || current.isNull());
    }
    return canonicalJsonTreeHash(read(stored)).equals(canonicalJsonTreeHash(current));
  }

  protected <T> T convert(JsonNode value, Class<T> type) {
    try {
      return objectMapper.treeToValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Stored inventory JSON has the wrong shape", exception);
    }
  }

  protected String json(JsonNode value) {
    return value == null || value.isNull() ? null : write(value);
  }

  protected String hash(String value) {
    return InventoryEventChecksum.sha256(value);
  }

  protected String canonicalHash(Object value) {
    return canonicalJson.sha256(value);
  }

  /**
   * PostgreSQL jsonb does not preserve object field order. Persisted immutable JSON facts must
   * therefore be hashed as ordinary map/list data rather than directly as an ObjectNode.
   */
  protected String canonicalJsonTreeHash(JsonNode value) {
    return canonicalHash(canonicalJsonValue(value));
  }

  protected Object canonicalJsonValue(JsonNode value) {
    try {
      return objectMapper.treeToValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory JSON cannot be canonicalized", exception);
    }
  }

  /** Narrow JSON operations available to a workflow without exposing the raw mapper. */
  protected static final class JsonOperations {
    private final ObjectMapper delegate;

    private JsonOperations(ObjectMapper delegate) {
      this.delegate = delegate;
    }

    ObjectNode createObjectNode() {
      return delegate.createObjectNode();
    }

    ArrayNode createArrayNode() {
      return delegate.createArrayNode();
    }

    <T extends JsonNode> T valueToTree(Object value) {
      return delegate.valueToTree(value);
    }
  }

  /** Narrow warehouse-authorization operations available to an inventory workflow. */
  protected static final class AuthorizationOperations {
    private final InventoryAuthorizer delegate;

    private AuthorizationOperations(InventoryAuthorizer delegate) {
      this.delegate = delegate;
    }

    void requireRead(Jwt jwt, UUID warehouseId) {
      delegate.requireRead(jwt, warehouseId);
    }

    void requireEdit(Jwt jwt, UUID warehouseId) {
      delegate.requireEdit(jwt, warehouseId);
    }

    void requireManage(Jwt jwt, UUID warehouseId) {
      delegate.requireManage(jwt, warehouseId);
    }

    UUID subjectId(Jwt jwt) {
      return delegate.subjectId(jwt);
    }

    String profileRevision(Jwt jwt) {
      return delegate.profileRevision(jwt);
    }

    String displayName(Jwt jwt) {
      return delegate.displayName(jwt);
    }

    InventoryAuthorizer.WarehouseScope readScope(Jwt jwt) {
      return delegate.readScope(jwt);
    }

    InventoryAuthorizer.WarehouseScope editScope(Jwt jwt) {
      return delegate.editScope(jwt);
    }

    InventoryAuthorizer.WarehouseScope manageScope(Jwt jwt) {
      return delegate.manageScope(jwt);
    }
  }
}
