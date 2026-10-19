package dev.buhanzaz.rwms.warehouse.service;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseTimeZoneHistory;
import dev.buhanzaz.rwms.warehouse.repository.WarehouseTimeZoneHistoryRepository;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves and appends immutable effective-dated warehouse timezone decisions.
 *
 * <p>Writes participate in the surrounding warehouse transaction. Reads use the database clock
 * and canonical microsecond UTC instants so API requests and PostgreSQL comparisons share one
 * temporal representation.
 */
@Service
public class WarehouseTimeZoneHistoryService {
  private final WarehouseTimeZoneHistoryRepository history;
  private final JdbcTemplate jdbc;

  /**
   * Creates the effective-dated timezone boundary.
   *
   * @param history repository for append-only timezone decisions
   * @param jdbc access to PostgreSQL's authoritative clock
   */
  public WarehouseTimeZoneHistoryService(
      WarehouseTimeZoneHistoryRepository history, JdbcTemplate jdbc) {
    this.history = history;
    this.jdbc = jdbc;
  }

  /**
   * Initializes the first timezone decision at warehouse creation time.
   *
   * @param warehouse newly persisted warehouse
   * @param recordedAt database timestamp for the decision audit
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void initialize(Warehouse warehouse, OffsetDateTime recordedAt) {
    history.saveAndFlush(
        WarehouseTimeZoneHistory.record(
            warehouse.getId(), canonicalTimestamp(warehouse.getCreatedAt()),
            Warehouse.requireCanonicalTimeZone(warehouse.getTimeZone()), canonicalTimestamp(recordedAt)));
  }

  /**
   * Appends one immutable timezone decision; an equal effective instant is a conflict.
   *
   * @param warehouseId stable warehouse identity
   * @param effectiveFrom effective instant for the new zone
   * @param timeZone canonical IANA timezone
   * @param recordedAt database timestamp for the decision audit
   * @return persisted immutable timezone entry
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public WarehouseTimeZoneHistory append(
      UUID warehouseId, OffsetDateTime effectiveFrom, ZoneId timeZone, OffsetDateTime recordedAt) {
    try {
      return history.saveAndFlush(
          WarehouseTimeZoneHistory.record(
              warehouseId,
              canonicalTimestamp(effectiveFrom),
              timeZone,
              canonicalTimestamp(recordedAt)));
    } catch (DataIntegrityViolationException exception) {
      throw new WarehouseConflictException(
          "A timezone decision already exists for this warehouse and effective timestamp");
    }
  }

  /**
   * Resolves the latest decision at or before the supplied instant.
   *
   * @param warehouseId stable warehouse identity
   * @param asOf instant to resolve
   * @return effective immutable timezone entry
   */
  @Transactional(readOnly = true)
  public WarehouseTimeZoneHistory effectiveAt(UUID warehouseId, OffsetDateTime asOf) {
    return history
        .findFirstByWarehouseIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            warehouseId, canonicalTimestamp(asOf))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Requested timezone timestamp precedes warehouse creation"));
  }

  /**
   * Resolves the timezone currently effective at a supplied database instant.
   *
   * @param warehouseId stable warehouse identity
   * @param now database instant used as the current time
   * @return current canonical IANA timezone
   */
  @Transactional(readOnly = true)
  public String currentTimeZone(UUID warehouseId, OffsetDateTime now) {
    return effectiveAt(warehouseId, now).getTimeZone();
  }

  /**
   * Resolves effective timezone strings for a collection at one shared instant.
   *
   * @param warehouseIds warehouse identities to resolve
   * @param asOf instant to resolve
   * @return immutable identity-to-timezone map
   */
  @Transactional(readOnly = true)
  public Map<UUID, String> effectiveTimeZonesAt(
      Collection<UUID> warehouseIds, OffsetDateTime asOf) {
    if (warehouseIds.isEmpty()) return Map.of();
    Map<UUID, String> result = new HashMap<>();
    for (WarehouseTimeZoneHistory entry :
        history.findEffectiveAt(warehouseIds, canonicalTimestamp(asOf))) {
      result.put(entry.getWarehouseId(), entry.getTimeZone());
    }
    if (result.size() != warehouseIds.size()) {
      throw new IllegalStateException("Warehouse timezone history is incomplete");
    }
    return Map.copyOf(result);
  }

  /**
   * Reads PostgreSQL's clock rather than an application-server clock for lifecycle fencing.
   *
   * @return canonical current database instant
   */
  public OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) throw new IllegalStateException("PostgreSQL did not return the current timestamp");
    return canonicalTimestamp(value);
  }

  /**
   * Converts an instant to the UTC microsecond precision stored and compared by PostgreSQL.
   *
   * @param value instant to canonicalize
   * @return UTC instant truncated to microsecond precision
   */
  public static OffsetDateTime canonicalTimestamp(OffsetDateTime value) {
    if (value == null) throw new IllegalArgumentException("timestamp is required");
    return value.toInstant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
