package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsDocumentHistoryView;
import dev.buhanzaz.rwms.logistics.api.LogisticsDocumentHistoryView.Event;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsAggregateType;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsDocumentResponseMapper;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads bounded owner-journal pages and saved line evidence without downstream calls or mutation.
 */
@Service
@RequiredArgsConstructor
public class LogisticsDocumentHistoryService {
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository lines;
  private final LogisticsDocumentResponseMapper mapper;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /**
   * Caller must authorize the document warehouse. Repeatable read keeps each page and its line
   * evidence at one document version; clients detect a version change between successive pages.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public LogisticsDocumentHistoryView read(
      UUID documentId, LogisticsDocumentType type, long afterVersion, int size) {
    if (afterVersion < -1 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Invalid logistics history cursor or page size");
    }
    var document =
        documents
            .findByIdAndDocumentType(documentId, type)
            .orElseThrow(LogisticsNotFoundException::new);
    List<Event> page =
        jdbc.query(
            """
            select event_id,aggregate_version,event_type,occurred_at,recorded_at,baseline,
              actor_ref::text,payload->>'state' as state,payload->>'resultCode' as result_code
            from domain_event
            where aggregate_type=? and aggregate_id=? and aggregate_version>? and aggregate_version<=?
            order by aggregate_version asc limit ?
            """,
            (result, row) -> {
              String actor = result.getString("actor_ref");
              return new Event(
                  result.getObject("event_id", UUID.class),
                  result.getLong("aggregate_version"),
                  result.getString("event_type"),
                  result.getObject("occurred_at", OffsetDateTime.class),
                  result.getObject("recorded_at", OffsetDateTime.class),
                  result.getBoolean("baseline"),
                  actor == null ? null : objectMapper.readValue(actor, OpaqueActorReference.class),
                  LogisticsDocumentState.valueOf(result.getString("state")),
                  result.getString("result_code"));
            },
            LogisticsAggregateType.from(type).name(),
            documentId.toString(),
            afterVersion,
            document.getVersion(),
            size + 1);
    boolean hasMore = page.size() > size;
    List<Event> events = List.copyOf(page.subList(0, Math.min(page.size(), size)));
    return new LogisticsDocumentHistoryView(
        documentId,
        document.getWarehouseId(),
        type,
        document.getVersion(),
        lines.findAllByDocument_IdOrderByLineNumber(documentId).stream()
            .map(mapper::toHistoryLine)
            .toList(),
        events,
        hasMore ? events.getLast().aggregateVersion() : null);
  }
}
