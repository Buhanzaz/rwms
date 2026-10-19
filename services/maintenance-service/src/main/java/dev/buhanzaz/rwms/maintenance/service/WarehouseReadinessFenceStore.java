package dev.buhanzaz.rwms.maintenance.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomic local fence between maintenance blockers and remote lifecycle-readiness confirmation. */
@Repository
public class WarehouseReadinessFenceStore {
  private final JdbcTemplate jdbc;
  private final WarehouseLifecycleBlockerStore blockers;

  public WarehouseReadinessFenceStore(
      JdbcTemplate jdbc, WarehouseLifecycleBlockerStore blockers) {
    this.jdbc = jdbc;
    this.blockers = blockers;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BeginResult begin(UUID warehouseId, long warehouseVersion) {
    requireIdentity(warehouseId, warehouseVersion);
    advisoryLock(warehouseId);
    FenceSnapshot active = activeForUpdate(warehouseId).orElse(null);
    if (active != null
        && active.warehouseVersion() == warehouseVersion
        && "SEALED".equals(active.state())) {
      return new BeginResult(BeginState.SEALED, active);
    }
    if (active != null && "SEALED".equals(active.state())) {
      throw new MaintenanceConflictException(
          "WAREHOUSE_READINESS_ALREADY_SEALED",
          "Maintenance readiness is already sealed for another warehouse version");
    }
    if (active != null && active.warehouseVersion() != warehouseVersion) {
      releaseRow(active, "REMOTE_VERSION_SUPERSEDED");
      active = null;
    }
    if (blockers.hasBlockers(warehouseId)) {
      return new BeginResult(BeginState.BLOCKED, active);
    }
    if (active != null) return new BeginResult(BeginState.FENCED, active);

    OffsetDateTime fencedAt = now();
    int changed = jdbc.update(
        """
        insert into warehouse_readiness_fence(
          id,warehouse_id,warehouse_version,state,fenced_at,updated_at)
        values (?,?,?,'FENCED',?,?)
        on conflict (warehouse_id,warehouse_version) do update
          set state='FENCED',release_reason=null,sealed_at=null,released_at=null,
              fenced_at=excluded.fenced_at,updated_at=excluded.updated_at
          where warehouse_readiness_fence.state='RELEASED'
        """,
        UUID.randomUUID(),
        warehouseId,
        warehouseVersion,
        fencedAt,
        fencedAt);
    if (changed != 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Warehouse readiness fence changed concurrently");
    }
    return new BeginResult(
        BeginState.FENCED,
        activeForUpdate(warehouseId).orElseThrow());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean seal(UUID warehouseId, long warehouseVersion) {
    requireIdentity(warehouseId, warehouseVersion);
    advisoryLock(warehouseId);
    FenceSnapshot active = activeForUpdate(warehouseId).orElse(null);
    if (active != null
        && active.warehouseVersion() == warehouseVersion
        && "SEALED".equals(active.state())) {
      return false;
    }
    OffsetDateTime sealedAt = now();
    int changed = jdbc.update(
        """
        update warehouse_readiness_fence
           set state='SEALED',sealed_at=?,updated_at=?
         where warehouse_id=? and warehouse_version=? and state='FENCED'
        """,
        sealedAt,
        sealedAt,
        warehouseId,
        warehouseVersion);
    if (changed != 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Warehouse readiness fence changed before sealing");
    }
    return true;
  }

  /** Releases only the exact unsealed version rejected by warehouse-service. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean release(UUID warehouseId, long warehouseVersion, String reason) {
    requireIdentity(warehouseId, warehouseVersion);
    String normalizedReason = normalizeReason(reason);
    advisoryLock(warehouseId);
    FenceSnapshot row = jdbc.query(
            """
            select * from warehouse_readiness_fence
             where warehouse_id=? and warehouse_version=?
             for update
            """,
            this::map,
            warehouseId,
            warehouseVersion)
        .stream()
        .findFirst()
        .orElse(null);
    if (row == null || !"FENCED".equals(row.state())) return false;
    releaseRow(row, normalizedReason);
    return true;
  }

  @Transactional(readOnly = true)
  public Optional<FenceSnapshot> active(UUID warehouseId) {
    if (warehouseId == null) throw new IllegalArgumentException("Warehouse identity is required");
    return jdbc.query(
            """
            select * from warehouse_readiness_fence
             where warehouse_id=? and state in ('FENCED','SEALED')
            """,
            this::map,
            warehouseId)
        .stream()
        .findFirst();
  }

  private Optional<FenceSnapshot> activeForUpdate(UUID warehouseId) {
    return jdbc.query(
            """
            select * from warehouse_readiness_fence
             where warehouse_id=? and state in ('FENCED','SEALED')
             for update
            """,
            this::map,
            warehouseId)
        .stream()
        .findFirst();
  }

  private void releaseRow(FenceSnapshot row, String reason) {
    OffsetDateTime releasedAt = now();
    int changed = jdbc.update(
        """
        update warehouse_readiness_fence
           set state='RELEASED',release_reason=?,released_at=?,updated_at=?
         where id=? and state='FENCED'
        """,
        normalizeReason(reason),
        releasedAt,
        releasedAt,
        row.id());
    if (changed != 1) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT", "Warehouse readiness fence changed before release");
    }
  }

  private void advisoryLock(UUID warehouseId) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        "maintenance:warehouse-readiness:" + warehouseId);
  }

  private FenceSnapshot map(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
    return new FenceSnapshot(
        rs.getObject("id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getLong("warehouse_version"),
        rs.getString("state"),
        rs.getString("release_reason"),
        rs.getObject("fenced_at", OffsetDateTime.class),
        rs.getObject("sealed_at", OffsetDateTime.class),
        rs.getObject("released_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }

  private static void requireIdentity(UUID warehouseId, long warehouseVersion) {
    if (warehouseId == null || warehouseVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness fence identity is invalid");
    }
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Warehouse readiness fence release reason is required");
    }
    String normalized = reason.trim().toUpperCase(java.util.Locale.ROOT)
        .replaceAll("[^A-Z0-9_]+", "_")
        .replaceAll("^_+|_+$", "");
    if (normalized.isBlank()) normalized = "REMOTE_REJECTION";
    return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  public enum BeginState { BLOCKED, FENCED, SEALED }

  public record BeginResult(BeginState state, FenceSnapshot fence) {}

  public record FenceSnapshot(
      UUID id,
      UUID warehouseId,
      long warehouseVersion,
      String state,
      String releaseReason,
      OffsetDateTime fencedAt,
      OffsetDateTime sealedAt,
      OffsetDateTime releasedAt,
      OffsetDateTime updatedAt) {}
}
