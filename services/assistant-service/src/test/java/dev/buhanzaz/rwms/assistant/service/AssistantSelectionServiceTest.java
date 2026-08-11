package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Covers authoritative removal and fail-closed recovery of persisted search carousels. */
class AssistantSelectionServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void malformedRecoveredCarouselFailsClosedWithoutCastingErrors() {
    AssistantSelectionService service =
        new AssistantSelectionService(mock(LogisticsClient.class), mapper);
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID heldId = UUID.randomUUID();
    AssistantApiModels.CabinSelectionResponse selection =
        new AssistantApiModels.CabinSelectionResponse(
            inquiryId,
            warehouseId,
            OffsetDateTime.parse("2030-08-09T12:00:00Z"),
            List.of(heldId),
            List.of());

    assertThat(service.filterRecoveredSearch(mapper.readTree("[]"), selection)).isNull();
    assertThat(service.filterRecoveredSearch(mapper.readTree("{\"data\":[]}"), selection)).isNull();
    assertThat(
            service.filterRecoveredSearch(
                mapper.readTree(
                    """
                    {"data":{"warehouseId":"%s","groups":[7,{"cabins":{}},
                      {"cabins":[{"id":"%s"}] }]}}
                    """
                        .formatted(warehouseId, UUID.randomUUID())),
                selection))
        .isNull();
  }

  @Test
  void removeByExactCurrentNumberSendsOnlyRetainedIdsAndReportsReleasedId() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), null, warehouseId, "ACTIVE"));
    when(logistics.readCabinSelection(inquiryId, "bearer"))
        .thenReturn(selection(inquiryId, warehouseId, List.of(firstId, secondId)));
    when(logistics.replaceCabinSelection(
            eq(inquiryId),
            eq(idempotencyKey),
            eq(warehouseId),
            org.mockito.ArgumentMatchers.anyList(),
            eq("bearer")))
        .thenReturn(selection(inquiryId, warehouseId, List.of(secondId)));
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    JsonNode result =
        service.remove(inquiryId, idempotencyKey, List.of(), List.of("CAB-" + firstId), "bearer");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UUID>> retained = ArgumentCaptor.forClass(List.class);
    verify(logistics)
        .replaceCabinSelection(
            eq(inquiryId), eq(idempotencyKey), eq(warehouseId), retained.capture(), eq("bearer"));
    assertThat(retained.getValue()).containsExactly(secondId);
    assertThat(result.path("data").path("removedRentalItemIds"))
        .extracting(JsonNode::asText)
        .containsExactly(firstId.toString());
  }

  @Test
  void replaceRejectsWarehouseDifferentFromCurrentInquiryContext() {
    UUID inquiryId = UUID.randomUUID();
    UUID fixedWarehouseId = UUID.randomUUID();
    UUID otherWarehouseId = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), UUID.randomUUID(), fixedWarehouseId, "ACTIVE"));
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    assertThatThrownBy(
            () ->
                service.replace(
                    inquiryId,
                    UUID.randomUUID(),
                    new AssistantApiModels.CabinSelectionRequest(
                        otherWarehouseId, List.of(UUID.randomUUID())),
                    "bearer"))
        .isInstanceOf(AssistantConflictException.class)
        .hasMessageContaining("another warehouse");
    verify(logistics, never()).replaceCabinSelection(any(), any(), any(), any(), any());
  }

  private LogisticsClient.CabinSelection selection(
      UUID inquiryId, UUID warehouseId, List<UUID> ids) {
    return new LogisticsClient.CabinSelection(
        inquiryId,
        warehouseId,
        ids.isEmpty() ? null : OffsetDateTime.parse("2030-08-09T12:00:00Z"),
        ids,
        ids.stream()
            .map(
                id ->
                    (JsonNode)
                        mapper
                            .createObjectNode()
                            .put("id", id.toString())
                            .put("number", "CAB-" + id))
            .toList());
  }
}
