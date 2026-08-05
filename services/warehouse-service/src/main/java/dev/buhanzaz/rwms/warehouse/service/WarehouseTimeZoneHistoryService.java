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

@Service
public class WarehouseTimeZoneHistoryService {
  private final WarehouseTimeZoneHistoryRepository history;
  private final JdbcTemplate jdbc;

  public WarehouseTimeZoneHistoryService(
      WarehouseTimeZoneHistoryRepository history, JdbcTemplate jdbc) {
    this.history = history;
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void initialize(Warehouse warehouse, OffsetDateTime recordedAt) {
    history.saveAndFlush(
        WarehouseTimeZoneHistory.record(
            warehouse.getId(), canonicalTimestamp(warehouse.getCreatedAt()),
            Warehouse.requireCanonicalTimeZone(warehouse.getTimeZone()), canonicalTimestamp(recordedAt)));
  }

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

  @Transactional(readOnly = true)
  public String currentTimeZone(UUID warehouseId, OffsetDateTime now) {
    return effectiveAt(warehouseId, now).getTimeZone();
  }

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

  public OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (value == null) throw new IllegalStateException("PostgreSQL did not return the current timestamp");
    return canonicalTimestamp(value);
  }

  public static OffsetDateTime canonicalTimestamp(OffsetDateTime value) {
    if (value == null) throw new IllegalArgumentException("timestamp is required");
    return value.toInstant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
