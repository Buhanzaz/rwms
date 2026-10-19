package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceActorReferenceProvider;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Provides JSON canonicalization, replay keys, transaction-boundary guards and other low-level command mechanics. */
@Service
final class MaintenanceCommandSupport {
  static final Duration LEASE_RENEWAL_GUARD = Duration.ofMinutes(5);
  private final MaintenanceActorReferenceProvider actorReferences;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  MaintenanceCommandSupport(
      MaintenanceActorReferenceProvider actorReferences,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.actorReferences = actorReferences;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  protected static UUID uuidField(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException("Reconciliation payload is missing " + field);
    }
    try {
      return UUID.fromString(value.stringValue());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Reconciliation payload has invalid " + field, exception);
    }
  }

  protected static String stringField(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual() || value.stringValue().isBlank()) {
      throw new IllegalStateException("Reconciliation payload is missing " + field);
    }
    return value.stringValue();
  }

  protected static List<UUID> uuidListField(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalStateException("Reconciliation payload is missing " + field);
    }
    List<UUID> result = new ArrayList<>();
    value.forEach(
        item -> {
          if (!item.isTextual()) {
            throw new IllegalStateException(
                "Reconciliation payload has invalid " + field);
          }
          try {
            result.add(UUID.fromString(item.stringValue()));
          } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Reconciliation payload has invalid " + field, exception);
          }
        });
    if (result.size() != new HashSet<>(result).size()) {
      throw new IllegalStateException("Reconciliation payload has duplicate " + field);
    }
    return List.copyOf(result);
  }

  protected static void assertVersion(long actual, long expected) {
    if (actual != expected) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Maintenance aggregate version conflict");
    }
  }

  protected String hash(Object value) {
    try {
      String serialized = mapper.writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, serialized);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize maintenance request JSON");
      }
      return MaintenanceChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Maintenance request cannot be hashed", exception);
    }
  }

  protected String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Maintenance value cannot be serialized", exception);
    }
  }

  protected String actorJson() {
    var actor = actorReferences.current();
    if (actor != null) return write(actor);
    return write(Map.of(
        "subjectId", "00000000-0000-0000-0000-0000000000d6",
        "principalType", "SYSTEM",
        "profileRevision", "00000000-0000-0000-0000-0000000000d6"));
  }

  protected static UUID rootId(MaintenanceRepair value) {
    return value.getRootRepairId() == null ? value.getId() : value.getRootRepairId();
  }

  protected ActorSnapshot actor(String storedActor) {
    Map<String, Object> value = jsonMap(storedActor);
    Object actorId = value.get("subjectId");
    if (actorId == null) actorId = value.get("actorId");
    String principalType = String.valueOf(value.getOrDefault("principalType", value.get("actorType")));
    return new ActorSnapshot(
        actorId == null ? "00000000-0000-0000-0000-0000000000d6" : actorId.toString(),
        "USER".equals(principalType) ? ActorType.USER : ActorType.SERVICE);
  }

  protected static long moneyToMinor(String value) {
    try {
      return new BigDecimal(value).movePointRight(2).longValueExact();
    } catch (ArithmeticException | NumberFormatException exception) {
      throw invalid("Money must contain exactly two fractional digits and fit int64 minor units");
    }
  }

  protected static String money(Long minor) {
    return minor == null ? null : money(minor.longValue());
  }

  protected static String money(long minor) {
    return BigDecimal.valueOf(minor, 2).toPlainString();
  }

  protected static String money(BigDecimal minor) {
    return money(minor.setScale(0, RoundingMode.HALF_UP).longValueExact());
  }

  protected static String quantity(BigDecimal value) {
    BigDecimal normalized = value.stripTrailingZeros();
    return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
  }

  protected static boolean booleanValue(Map<String, Object> value, String key) {
    return Boolean.TRUE.equals(value.get(key));
  }

  protected static int intValue(Map<String, Object> value, String key) {
    Object stored = value.get(key);
    return stored instanceof Number number ? number.intValue() : 0;
  }

  protected static String stringValue(Map<String, Object> value, String key) {
    Object stored = value.get(key);
    if (stored == null) {
      throw new IllegalStateException("Stored catalog validation report is missing " + key);
    }
    return stored.toString();
  }

  protected Map<String, Object> jsonMap(String value) {
    try {
      return mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance JSON is invalid", exception);
    }
  }

  protected <T> T read(JsonNode value, Class<T> type) {
    try {
      return mapper.treeToValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance response is invalid", exception);
    }
  }

  protected <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance value is invalid", exception);
    }
  }

  protected <T> List<T> readList(String value, Class<T> type) {
    try {
      JsonNode node = mapper.readTree(value);
      List<T> result = new ArrayList<>();
      for (JsonNode item : node) result.add(mapper.treeToValue(item, type));
      return List.copyOf(result);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored maintenance list is invalid", exception);
    }
  }

  protected static MaintenanceValidationException invalid(String message) {
    return new MaintenanceValidationException("MAINTENANCE_VALIDATION_FAILED", message);
  }

  protected static <T> T requireReconciliationResult(T value) {
    if (value == null) {
      throw new IllegalStateException("Reconciliation transaction did not return a result");
    }
    return value;
  }

  protected static void requireNoCallerTransaction(String operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Maintenance cannot " + operation + " inside a caller transaction");
    }
  }

  protected <T> T inLocalTransaction(String operation, Supplier<T> action) {
    T result = transactions.execute(status -> action.get());
    if (result == null) {
      throw new IllegalStateException("Maintenance " + operation + " transaction was empty");
    }
    return result;
  }

  protected static UUID derived(UUID key, String suffix) {
    return UUID.nameUUIDFromBytes((key + ":" + suffix).getBytes(StandardCharsets.UTF_8));
  }

  protected static UUID stableOperationKey(String operation, UUID aggregateId, long version) {
    return UUID.nameUUIDFromBytes(
        (operation + ":" + aggregateId + ":" + version).getBytes(StandardCharsets.UTF_8));
  }

  protected static boolean leaseIsFresh(OffsetDateTime expiresAt) {
    return expiresAt != null
        && !expiresAt.isBefore(
            OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(LEASE_RENEWAL_GUARD));
  }

  protected static boolean leaseHasExpired(OffsetDateTime expiresAt) {
    return expiresAt == null
        || !expiresAt.isAfter(OffsetDateTime.now(java.time.ZoneOffset.UTC));
  }

  protected static void requireFreshDependencyLease(
      MaintenanceDependencyGateway.LeaseSnapshot lease) {
    if (!leaseIsFresh(lease.expiresAt())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Asset-service lease does not retain the five-minute safety window");
    }
  }

  protected void advisoryLock(String key) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        key);
  }
}
