package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentSummary;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsDocumentResponseMapper;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Verifies bounded document paging and one-query line materialization. */
class LogisticsDocumentPageReadProjectionTest {

  @Test
  void pageLoadsAllLinesForOnlyTheSelectedDocumentsWithOneBatchQuery() {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository lines = mock(LogisticsDocumentLineRepository.class);
    LogisticsDocumentResponseMapper mapper = mock(LogisticsDocumentResponseMapper.class);
    TransferPlanService transferPlans = mock(TransferPlanService.class);
    LogisticsDocumentReadProjection projection =
        new LogisticsDocumentReadProjection(documents, lines, mapper, transferPlans);
    UUID warehouseId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.of(2026, 9, 2);
    LogisticsDocument first = document(firstId);
    LogisticsDocument second = document(secondId);
    LogisticsDocumentLine firstLine = line(first);
    LogisticsDocumentLine secondLine = line(second);
    PageRequest request = PageRequest.of(1, 2);

    when(documents
            .findAllByDocumentTypeAndWarehouseIdAndScheduledDateOrderByCreatedAtDescIdDesc(
                LogisticsDocumentType.SHIPMENT, warehouseId, scheduledDate, request))
        .thenReturn(new PageImpl<>(List.of(first, second), request, 5));
    when(lines.findAllByDocumentIdIn(List.of(firstId, secondId)))
        .thenReturn(List.of(firstLine, secondLine));
    when(mapper.toSummary(first)).thenReturn(summary(firstId, warehouseId));
    when(mapper.toSummary(second)).thenReturn(summary(secondId, warehouseId));
    when(mapper.toLineViews(List.of(firstLine))).thenReturn(List.of());
    when(mapper.toLineViews(List.of(secondLine))).thenReturn(List.of());

    LogisticsDocumentPage result =
        projection.page(LogisticsDocumentType.SHIPMENT, warehouseId, scheduledDate, 1, 2);

    assertThat(result.page()).isEqualTo(1);
    assertThat(result.pageSize()).isEqualTo(2);
    assertThat(result.totalElements()).isEqualTo(5);
    assertThat(result.totalPages()).isEqualTo(3);
    assertThat(result.hasNext()).isTrue();
    assertThat(result.content()).extracting(view -> view.id()).containsExactly(firstId, secondId);
    verify(lines).findAllByDocumentIdIn(List.of(firstId, secondId));
    verify(lines, never()).findAllByDocument_IdOrderByLineNumber(any());
  }

  @Test
  void transferPageUsesTheBidirectionalWarehouseQueryForTheSelectedDay() {
    LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
    LogisticsDocumentLineRepository lines = mock(LogisticsDocumentLineRepository.class);
    LogisticsDocumentResponseMapper mapper = mock(LogisticsDocumentResponseMapper.class);
    TransferPlanService transferPlans = mock(TransferPlanService.class);
    LogisticsDocumentReadProjection projection =
        new LogisticsDocumentReadProjection(documents, lines, mapper, transferPlans);
    UUID warehouseId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.of(2026, 9, 3);
    PageRequest request = PageRequest.of(0, 50);

    when(documents.findTransferPageForWarehouseAndScheduledDate(
            LogisticsDocumentType.TRANSFER, warehouseId, scheduledDate, request))
        .thenReturn(new PageImpl<>(List.of(), request, 0));

    LogisticsDocumentPage result =
        projection.page(LogisticsDocumentType.TRANSFER, warehouseId, scheduledDate, 0, 50);

    assertThat(result.content()).isEmpty();
    verify(documents)
        .findTransferPageForWarehouseAndScheduledDate(
            LogisticsDocumentType.TRANSFER, warehouseId, scheduledDate, request);
    verify(lines, never()).findAllByDocumentIdIn(any());
  }

  private static LogisticsDocument document(UUID id) {
    LogisticsDocument document = mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(id);
    return document;
  }

  private static LogisticsDocumentLine line(LogisticsDocument document) {
    LogisticsDocumentLine line = mock(LogisticsDocumentLine.class);
    when(line.getDocument()).thenReturn(document);
    return line;
  }

  private static LogisticsDocumentSummary summary(UUID id, UUID warehouseId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new LogisticsDocumentSummary(
        id,
        0,
        LogisticsDocumentType.SHIPMENT,
        LogisticsDocumentState.DRAFT,
        warehouseId,
        null,
        "Клиент",
        "Водитель",
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        now,
        now);
  }
}
