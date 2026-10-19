package dev.buhanzaz.rwms.logistics.retention.persistence;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Counts terminal transport rows eligible for the non-destructive retention report. */
@Component
@RequiredArgsConstructor
public class LogisticsRetentionCandidateReader {
  private final JdbcTemplate jdbc;

  public long countPublishedOutboxRows(OffsetDateTime cutoff) {
    return count(
            "select count(*) from outbox_event where status = 'PUBLISHED' and published_at < ?",
            cutoff)
        + count(
            "select count(*) from rental_inquiry_outbox where status = 'PUBLISHED' and"
                + " published_at < ?",
            cutoff)
        + count(
            "select count(*) from warehouse_operation_mark_outbox where state = 'CONFIRMED' and"
                + " updated_at < ?",
            cutoff);
  }

  public long countProcessedInboxRows(OffsetDateTime cutoff) {
    return count(
        "select count(*) from inbox_message where status = 'PROCESSED' and processed_at < ?",
        cutoff);
  }

  private long count(String sql, OffsetDateTime cutoff) {
    Long value = jdbc.queryForObject(sql, Long.class, cutoff);
    return value == null ? 0 : value;
  }
}
