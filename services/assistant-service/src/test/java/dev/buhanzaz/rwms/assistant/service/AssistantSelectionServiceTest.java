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

  @Test
  void replaceAcceptsAuthoritativeEmptyReleaseWithoutAWarehouse() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), null, warehouseId, "ACTIVE"));
    when(logistics.replaceCabinSelection(
            inquiryId, idempotencyKey, warehouseId, List.of(), "bearer"))
        .thenReturn(new LogisticsClient.CabinSelection(inquiryId, null, null, List.of(), List.of()));
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    AssistantApiModels.CabinSelectionResponse result =
        service.replace(
            inquiryId,
            idempotencyKey,
            new AssistantApiModels.CabinSelectionRequest(warehouseId, List.of()),
            "bearer");

    assertThat(result.warehouseId()).isNull();
    assertThat(result.expiresAt()).isNull();
    assertThat(result.rentalItemIds()).isEmpty();
    assertThat(result.items()).isEmpty();
  }

  @Test
  void replaceRejectsAnEmptyAuthoritativeSelectionForANonEmptyRequest() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), null, warehouseId, "ACTIVE"));
    when(logistics.replaceCabinSelection(
            inquiryId, idempotencyKey, warehouseId, List.of(rentalItemId), "bearer"))
        .thenReturn(new LogisticsClient.CabinSelection(inquiryId, null, null, List.of(), List.of()));
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    assertThatThrownBy(
            () ->
                service.replace(
                    inquiryId,
                    idempotencyKey,
                    new AssistantApiModels.CabinSelectionRequest(
                        warehouseId, List.of(rentalItemId)),
                    "bearer"))
        .isInstanceOf(AssistantUpstreamException.class)
        .hasMessage("Logistics returned another cabin selection");
  }

  @Test
  void replaceRejectsANonEmptyAuthoritativeSelectionForAReleaseRequest() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), null, warehouseId, "ACTIVE"));
    when(logistics.replaceCabinSelection(
            inquiryId, idempotencyKey, warehouseId, List.of(), "bearer"))
        .thenReturn(selection(inquiryId, warehouseId, List.of(rentalItemId)));
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    assertThatThrownBy(
            () ->
                service.replace(
                    inquiryId,
                    idempotencyKey,
                    new AssistantApiModels.CabinSelectionRequest(warehouseId, List.of()),
                    "bearer"))
        .isInstanceOf(AssistantUpstreamException.class)
        .hasMessage("Logistics returned another cabin selection");
  }

  @Test
  void replaceStillRejectsAnotherWarehouseForANonEmptyAuthoritativeSelection() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID otherWarehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), null, warehouseId, "ACTIVE"));
    when(logistics.replaceCabinSelection(
            inquiryId, idempotencyKey, warehouseId, List.of(rentalItemId), "bearer"))
        .thenReturn(selection(inquiryId, otherWarehouseId, List.of(rentalItemId)));
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    assertThatThrownBy(
            () ->
                service.replace(
                    inquiryId,
                    idempotencyKey,
                    new AssistantApiModels.CabinSelectionRequest(
                        warehouseId, List.of(rentalItemId)),
                    "bearer"))
        .isInstanceOf(AssistantUpstreamException.class)
        .hasMessage("Logistics returned another selection warehouse");
  }

  @Test
  void currentRejectsAnotherInquiryIdentity() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();

    assertCurrentSelectionRejected(
        inquiryId, selection(UUID.randomUUID(), warehouseId, List.of(rentalItemId)));
  }

  @Test
  void replaceRejectsAnotherInquiryIdentity() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();

    assertReplaceSelectionRejected(
        inquiryId,
        warehouseId,
        List.of(rentalItemId),
        selection(UUID.randomUUID(), warehouseId, List.of(rentalItemId)));
  }

  @Test
  void replaceRejectsAuthoritativeIdsDifferentFromTheRequestedOrder() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();

    assertReplaceSelectionRejected(
        inquiryId,
        warehouseId,
        List.of(UUID.randomUUID()),
        selection(inquiryId, warehouseId, List.of(UUID.randomUUID())));
  }

  @Test
  void replaceRejectsItemsWhoseIdsDoNotFollowTheAuthoritativeOrder() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<UUID> ids = List.of(firstId, secondId);
    LogisticsClient.CabinSelection reorderedItems =
        selection(
            inquiryId,
            warehouseId,
            ids,
            List.of(cabinItem(secondId, warehouseId), cabinItem(firstId, warehouseId)));

    assertReplaceSelectionRejected(inquiryId, warehouseId, ids, reorderedItems);
  }

  @Test
  void replaceRejectsAnItemFromAnotherWarehouse() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    LogisticsClient.CabinSelection wrongItemWarehouse =
        selection(
            inquiryId,
            warehouseId,
            List.of(rentalItemId),
            List.of(cabinItem(rentalItemId, UUID.randomUUID())));

    assertReplaceSelectionRejected(
        inquiryId, warehouseId, List.of(rentalItemId), wrongItemWarehouse);
  }

  @Test
  void currentWrapsAMalformedItemIdAsAnUpstreamFailure() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    JsonNode malformed =
        mapper
            .createObjectNode()
            .put("id", "not-a-uuid")
            .put("warehouseId", warehouseId.toString());

    assertCurrentSelectionRejected(
        inquiryId,
        selection(inquiryId, warehouseId, List.of(rentalItemId), List.of(malformed)));
  }

  @Test
  void currentWrapsAMalformedItemWarehouseAsAnUpstreamFailure() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    JsonNode malformed =
        mapper
            .createObjectNode()
            .put("id", rentalItemId.toString())
            .put("warehouseId", "not-a-uuid");

    assertCurrentSelectionRejected(
        inquiryId,
        selection(inquiryId, warehouseId, List.of(rentalItemId), List.of(malformed)));
  }

  @Test
  void currentRejectsDuplicateAuthoritativeIds() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();

    assertCurrentSelectionRejected(
        inquiryId,
        selection(
            inquiryId,
            warehouseId,
            List.of(rentalItemId, rentalItemId),
            List.of(
                cabinItem(rentalItemId, warehouseId), cabinItem(rentalItemId, warehouseId))));
  }

  @Test
  void currentRejectsDifferentIdAndItemCardinality() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    LogisticsClient.CabinSelection malformed = mock(LogisticsClient.CabinSelection.class);
    when(malformed.inquiryId()).thenReturn(inquiryId);
    when(malformed.warehouseId()).thenReturn(warehouseId);
    when(malformed.rentalItemIds()).thenReturn(List.of(UUID.randomUUID()));
    when(malformed.items()).thenReturn(List.of());

    assertCurrentSelectionRejected(inquiryId, malformed);
  }

  @Test
  void currentRejectsAnEmptySelectionRetainingItsWarehouse() {
    UUID inquiryId = UUID.randomUUID();

    assertCurrentSelectionRejected(
        inquiryId,
        new LogisticsClient.CabinSelection(
            inquiryId, UUID.randomUUID(), null, List.of(), List.of()));
  }

  @Test
  void replaceRejectsAnEmptyReleaseResponseRetainingItsWarehouse() {
    assertEmptyReleaseResponseRejected(EmptyReleaseRemainder.WAREHOUSE);
  }

  @Test
  void replaceRejectsAnEmptyReleaseResponseRetainingItsExpiry() {
    assertEmptyReleaseResponseRejected(EmptyReleaseRemainder.EXPIRY);
  }

  @Test
  void replaceRejectsAnEmptyReleaseResponseRetainingItems() {
    assertEmptyReleaseResponseRejected(EmptyReleaseRemainder.ITEMS);
  }

  private void assertEmptyReleaseResponseRejected(EmptyReleaseRemainder remainder) {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    LogisticsClient.CabinSelection selection = mock(LogisticsClient.CabinSelection.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), null, warehouseId, "ACTIVE"));
    when(logistics.replaceCabinSelection(
            inquiryId, idempotencyKey, warehouseId, List.of(), "bearer"))
        .thenReturn(selection);
    when(selection.inquiryId()).thenReturn(inquiryId);
    when(selection.rentalItemIds()).thenReturn(List.of());
    when(selection.items())
        .thenReturn(
            remainder == EmptyReleaseRemainder.ITEMS
                ? List.of(mapper.createObjectNode().put("id", UUID.randomUUID().toString()))
                : List.of());
    when(selection.warehouseId())
        .thenReturn(remainder == EmptyReleaseRemainder.WAREHOUSE ? warehouseId : null);
    when(selection.expiresAt())
        .thenReturn(
            remainder == EmptyReleaseRemainder.EXPIRY
                ? OffsetDateTime.parse("2030-08-09T12:00:00Z")
                : null);
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    assertThatThrownBy(
            () ->
                service.replace(
                    inquiryId,
                    idempotencyKey,
                    new AssistantApiModels.CabinSelectionRequest(warehouseId, List.of()),
                    "bearer"))
        .isInstanceOf(AssistantUpstreamException.class)
        .hasMessageContaining("cabin selection");
  }

  private void assertCurrentSelectionRejected(
      UUID inquiryId, LogisticsClient.CabinSelection authoritativeSelection) {
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readCabinSelection(inquiryId, "bearer")).thenReturn(authoritativeSelection);
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    assertThatThrownBy(() -> service.current(inquiryId, "bearer"))
        .isInstanceOf(AssistantUpstreamException.class)
        .hasMessage("Logistics returned an invalid cabin selection");
  }

  private void assertReplaceSelectionRejected(
      UUID inquiryId,
      UUID warehouseId,
      List<UUID> requestedIds,
      LogisticsClient.CabinSelection authoritativeSelection) {
    UUID idempotencyKey = UUID.randomUUID();
    LogisticsClient logistics = mock(LogisticsClient.class);
    when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), null, warehouseId, "ACTIVE"));
    when(logistics.replaceCabinSelection(
            inquiryId, idempotencyKey, warehouseId, requestedIds, "bearer"))
        .thenReturn(authoritativeSelection);
    AssistantSelectionService service = new AssistantSelectionService(logistics, mapper);

    assertThatThrownBy(
            () ->
                service.replace(
                    inquiryId,
                    idempotencyKey,
                    new AssistantApiModels.CabinSelectionRequest(warehouseId, requestedIds),
                    "bearer"))
        .isInstanceOf(AssistantUpstreamException.class);
  }

  private LogisticsClient.CabinSelection selection(
      UUID inquiryId, UUID warehouseId, List<UUID> ids) {
    return selection(
        inquiryId,
        warehouseId,
        ids,
        ids.stream().map(id -> cabinItem(id, warehouseId)).toList());
  }

  private LogisticsClient.CabinSelection selection(
      UUID inquiryId, UUID warehouseId, List<UUID> ids, List<JsonNode> items) {
    return new LogisticsClient.CabinSelection(
        inquiryId,
        warehouseId,
        ids.isEmpty() ? null : OffsetDateTime.parse("2030-08-09T12:00:00Z"),
        ids,
        items);
  }

  private JsonNode cabinItem(UUID id, UUID warehouseId) {
    return mapper
        .createObjectNode()
        .put("id", id.toString())
        .put("warehouseId", warehouseId.toString())
        .put("number", "CAB-" + id);
  }

  private enum EmptyReleaseRemainder {
    WAREHOUSE,
    EXPIRY,
    ITEMS
  }
}
