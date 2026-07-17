package dev.buhanzaz.rwms.maintenance.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceIdempotencyCleanup {
  private final JdbcTemplate jdbc;

  public MaintenanceIdempotencyCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Scheduled(fixedDelayString = "${rwms.maintenance.idempotency.cleanup-delay:1h}")
  public void removeExpired() {
    jdbc.update("delete from maintenance_idempotency_record where expires_at <= clock_timestamp()");
  }
}
