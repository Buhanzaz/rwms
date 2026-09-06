package dev.buhanzaz.rwms.logistics.service.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsDocumentJournalReader.JournalEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class LogisticsDocumentJournalReaderTest {
  @Test
  @SuppressWarnings("unchecked")
  void appliesTheAggregateAndVersionBoundsToTheJournalPage() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper objectMapper = mock(ObjectMapper.class);
    UUID aggregateId = UUID.randomUUID();
    JournalEvent event =
        new JournalEvent(
            UUID.randomUUID(),
            5,
            "logistics.shipment.updated.v1",
            OffsetDateTime.parse("2026-09-01T09:00:00Z"),
            OffsetDateTime.parse("2026-09-01T09:00:01Z"),
            false,
            null,
            "IN_PROGRESS",
            null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(event));
    LogisticsDocumentJournalReader reader =
        new LogisticsDocumentJournalReader(jdbc, objectMapper);

    assertThat(reader.readPage("SHIPMENT", aggregateId, 3, 9, 51)).containsExactly(event);

    verify(jdbc)
        .query(
            org.mockito.ArgumentMatchers.argThat(
                sql ->
                    sql.contains("from domain_event")
                        && sql.contains("aggregate_version>?")
                        && sql.contains("aggregate_version<=?")),
            any(RowMapper.class),
            aryEq(new Object[] {"SHIPMENT", aggregateId.toString(), 3L, 9L, 51}));
  }
}
