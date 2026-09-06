package dev.buhanzaz.rwms.logistics.retention.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LogisticsRetentionCandidateReaderTest {
  @Test
  void countsOnlyTheFourApprovedTerminalTransportDatasets() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OffsetDateTime cutoff = OffsetDateTime.parse("2026-06-03T09:00:00Z");
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(cutoff)))
        .thenReturn(2L, 3L, 5L, 7L);
    LogisticsRetentionCandidateReader reader = new LogisticsRetentionCandidateReader(jdbc);

    assertThat(reader.countPublishedOutboxRows(cutoff)).isEqualTo(10L);
    assertThat(reader.countProcessedInboxRows(cutoff)).isEqualTo(7L);

    verify(jdbc, times(4)).queryForObject(anyString(), eq(Long.class), eq(cutoff));
  }
}
