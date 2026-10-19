package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsDocumentHistoryView;
import dev.buhanzaz.rwms.logistics.api.LogisticsDocumentHistoryView.Event;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsAggregateType;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsDocumentResponseMapper;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.persistence.LogisticsDocumentJournalReader;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads bounded owner-journal pages and saved line evidence without downstream calls or mutation.
 */
@Service
@RequiredArgsConstructor
public class LogisticsDocumentHistoryService {
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository lines;
  private final LogisticsDocumentResponseMapper mapper;
  private final LogisticsDocumentJournalReader journal;

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
        journal
            .readPage(
                LogisticsAggregateType.from(type).name(),
                documentId,
                afterVersion,
                document.getVersion(),
                size + 1)
            .stream()
            .map(
                event ->
                    new Event(
                        event.eventId(),
                        event.aggregateVersion(),
                        event.eventType(),
                        event.occurredAt(),
                        event.recordedAt(),
                        event.baseline(),
                        event.actor(),
                        LogisticsDocumentState.valueOf(event.state()),
                        event.resultCode()))
            .toList();
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
