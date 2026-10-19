package dev.buhanzaz.rwms.warehouse.service;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Immutable local audit in addition to the transactional outbox fact. */
@Service
public class WarehouseLifecycleAuditStore {
  private final JdbcTemplate jdbc;

  public WarehouseLifecycleAuditStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      Warehouse warehouse, WarehouseLifecycleTransition transition, OffsetDateTime recordedAt) {
    jdbc.update(
        """
        insert into warehouse_lifecycle_transition(
            warehouse_id,warehouse_version,transition,recorded_at)
        values (?, ?, ?, ?)
        """,
        warehouse.getId(),
        warehouse.getVersion(),
        transition.name(),
        recordedAt);
  }
}
